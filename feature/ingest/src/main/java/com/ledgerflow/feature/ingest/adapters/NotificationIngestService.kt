package com.ledgerflow.feature.ingest.adapters

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.usecase.IsPackageAllowedForIngestUseCase
import com.ledgerflow.feature.ingest.pipeline.IngestEventSink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The notification capture component, in **both** flavours (SPEC.md §5.2).
 *
 * This is the higher-recall source, not a fallback for the Play build: UPI apps,
 * card apps and banks all post transaction notifications, and many banks have
 * moved to notification-only for small-value UPI, which SMS misses entirely
 * (§3.1).
 *
 * ## The allowlist runs before the body is touched
 *
 * CLAUDE.md §7 states it as a hard rule, and §5.2 repeats it as a user-facing
 * promise:
 *
 * > The notification package allowlist filter runs **before** any notification
 * > body is read. Never log or persist content from a non-allowlisted package —
 * > this is a stated privacy guarantee, not an implementation detail.
 *
 * So [onNotificationPosted] reads `sbn.packageName` and nothing else until the
 * allowlist has answered. `sbn.notification.extras` is not touched, not logged
 * and not passed anywhere for a package that is not on the list — the extraction
 * below happens inside the allowed branch, after the `return`. That ordering is
 * the guarantee's entire implementation, and
 * `NotificationAllowlistOrderTest` fails the build if it is reversed.
 *
 * `isPackageAllowed` returns false when the vault is locked, which is the
 * correct answer rather than a limitation: reading a notification that could not
 * then be stored would break the rule for no benefit.
 *
 * ## Ongoing notifications are skipped
 *
 * A persistent "1 payment in progress" notification is re-posted repeatedly with
 * the same text. Capturing every repost would fill `notification_raw` with one
 * transaction and rely on the hash's minute bucket to sort it out. The interesting
 * event is the settled one.
 */
@AndroidEntryPoint
public class NotificationIngestService : NotificationListenerService() {

    @Inject internal lateinit var isPackageAllowed: IsPackageAllowedForIngestUseCase

    @Inject internal lateinit var sink: IngestEventSink

    @Inject internal lateinit var clock: Clock

    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher

    private val handler = CoroutineExceptionHandler { _, throwable ->
        // No content, ever -- not even in the failure path.
        Log.e(TAG, "Failed to hand off a captured notification", throwable)
    }

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher + handler) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return

        // Everything read before the allowlist answers: a package name, and two
        // flags that are metadata rather than content. Nothing from `extras`.
        val packageName = posted.packageName ?: return
        if (posted.isOngoing) return
        val postedAt = clock.nowMillis()

        scope.launch {
            if (!isPackageAllowed(packageName)) return@launch

            // Past this line, and only past it, the notification's contents may
            // be read.
            val event = posted.toIngestEvent(packageName, postedAt) ?: return@launch
            sink.submit(event)
        }
    }

    /**
     * Flattens the notification into the one shape the pipeline understands.
     *
     * §5.2 requires title + text + bigText + subText joined into a single body,
     * so that one regex ruleset runs against a notification and an SMS alike.
     * Duplicates are dropped: `bigText` is very often `text` repeated, and a
     * doubled body would skew every `contains` a rule performs.
     *
     * The app's **user-visible label** becomes `sender` (D-11) — "Google Pay",
     * not the package name repeated and not the notification's title. The title
     * is carried separately, because it is per-notification content and a poor
     * input to a dedupe key that has to be stable across two sources.
     *
     * Null when there is no text at all: a group summary or a media-style
     * notification is not a financial message, and an empty body would be a row
     * indistinguishable from one the retention purge has already emptied.
     */
    private fun StatusBarNotification.toIngestEvent(
        packageName: String,
        postedAt: Long,
    ): RawIngestEvent? {
        val extras: Bundle = notification?.extras ?: return null
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        val parts = listOfNotNull(
            title,
            extras.charSequence(Notification.EXTRA_TEXT),
            extras.charSequence(Notification.EXTRA_BIG_TEXT),
            extras.charSequence(Notification.EXTRA_SUB_TEXT),
        ).distinct()
        if (parts.isEmpty()) return null

        return RawIngestEvent(
            sourceType = IngestSourceType.NOTIFICATION,
            sender = appLabelFor(packageName),
            body = parts.joinToString(separator = " "),
            receivedAt = postedAt,
            packageName = packageName,
            title = title,
        )
    }

    /** "Google Pay" rather than `com.google...` (D-11). Falls back to the package. */
    private fun appLabelFor(packageName: String): String = runCatching {
        val manager = packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * OEM battery managers kill this service aggressively, and the system does
     * not re-bind on its own (§5.2).
     *
     * §5.2's "> 6h dead" dashboard health banner is the other half of this and
     * lands with the Dashboard work.
     */
    override fun onListenerDisconnected() {
        Log.d(TAG, "Listener disconnected; requesting rebind.")
        requestRebind(ComponentName(this, javaClass))
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Listener connected.")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "NotificationIngest"
    }
}

/** Blank-safe read. A notification extra can be present and empty, which is not text. */
private fun Bundle.charSequence(key: String): String? =
    getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
