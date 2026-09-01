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
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
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

    /** §5.2's liveness record, outside the vault (ADR-0020). */
    @Inject internal lateinit var listenerHealth: ListenerHealthStore

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
     * **Joined with a newline, not a space,** and that is load-bearing. A
     * notification's title is usually the whole transaction ("Paid Rs.240 to
     * Swiggy") and the text below it is chatter ("Transaction successful").
     * Space-joining destroys that boundary, and a lazy merchant capture then
     * runs straight through the chatter -- found by the golden corpus, which
     * read a merchant of "grocer@ybl Transaction successful". A newline keeps
     * the fields separable while still being one body for one ruleset.
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
            body = parts.joinToString(separator = "\n"),
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
     * `requestRebind` first, and the bookkeeping second: asking for the rebind
     * is the part that fixes anything, and this callback can be immediately
     * followed by the process ending. [ListenerHealthStore] is explicit that the
     * disk half here is best-effort for that reason — nothing in
     * `ListenerHealth.evaluate` requires a disconnect to have been recorded,
     * because the disconnect that matters most is the one that never gets to
     * write.
     *
     * The in-process flag inside the store is *not* best-effort: it is set by
     * [ListenerHealthStore.recordDisconnected] before the suspending write, and
     * a process that dies loses it in the only direction that is safe — a fresh
     * process starts disconnected.
     *
     * **The one thing assumed here is the platform's callback order**: an unbind
     * delivers this callback before `onDestroy`. That is what the framework
     * documents, and it is why [onDestroy] does not clear the flag itself — it
     * cannot, because it cancels [scope] and the clear is a suspending call.
     * If a future platform destroys the service without this callback while the
     * *process survives*, the store would keep reporting connected and the
     * banner would stay quiet — a false negative on the one signal it exists to
     * give. Not defended against because the defence (an
     * application-scoped coroutine, or a non-suspending flag on the port) costs
     * more than the undocumented path is worth; written down because a silent
     * assumption is how a health signal decays.
     */
    override fun onListenerDisconnected() {
        Log.d(TAG, "Listener disconnected; requesting rebind.")
        requestRebind(ComponentName(this, javaClass))
        val at = clock.nowMillis()
        scope.launch { listenerHealth.recordDisconnected(at) }
    }

    /**
     * The listener bound, and the only routine writer of §5.2's liveness record.
     *
     * **The timestamp is taken here, not inside the coroutine.** This runs at
     * bind time; the coroutine runs whenever the IO dispatcher gets to it, which
     * at boot can be meaningfully later on a loaded device. Recording when the
     * write happened rather than when the connection did would inflate every
     * interval the banner measures — and this is the value the six-hour
     * threshold is measured *from*.
     */
    override fun onListenerConnected() {
        Log.d(TAG, "Listener connected.")
        val at = clock.nowMillis()
        scope.launch { listenerHealth.recordConnected(at) }
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
