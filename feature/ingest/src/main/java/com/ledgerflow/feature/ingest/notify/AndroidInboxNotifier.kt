package com.ledgerflow.feature.ingest.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.inbox.InboxNotifier
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.GetPendingUseCase
import com.ledgerflow.feature.ingest.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §5.1's last pipeline step, on Android. P2-7.
 *
 * ```
 * insert into pending_transaction
 *   -> post Notification (channel "inbox_high", actions: [Approve] [Review] [Discard])
 *   -> tap -> ledgerflow://inbox/{pendingId} -> Review screen
 *   -> grouped notification when >3 pending
 * ```
 *
 * **What a locked screen shows is part of the privacy guarantee.** §5.2's rule
 * governs what LedgerFlow may *read*; the same care applies to what it puts on
 * a lock screen in a room full of people. The notification is
 * `VISIBILITY_PRIVATE` and carries a public version that names a count and
 * nothing else — no merchant, no amount, no bank. Unlocking reveals the rest.
 *
 * **Law 1 is untouched.** `[Approve]` goes through `ApprovePendingUseCase`
 * exactly as the review screen does, and the receiver that handles it holds no
 * other door. A notification is a surface, not a second writer. `[Approve]` is
 * an amendment to §5.1, which listed only the other two (owner, P2-7).
 */
@Singleton
internal class AndroidInboxNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val getPending: GetPendingUseCase,
    private val ledgerRepository: LedgerRepository,
) : InboxNotifier {

    override suspend fun notifyCandidate(pendingId: String) {
        // Posting is best-effort by design. The candidate is already on disk and
        // the Inbox shows it regardless -- a withheld POST_NOTIFICATIONS grant
        // must never become a reason the pipeline reports a failure.
        if (!canPost()) return

        val candidate = getPending(pendingId) ?: return
        // §3.1: a suppressed duplicate is retained and visible, never announced.
        // Checked here as well as at the call site because this is the method a
        // future caller will reach for, and the call site's `when` is not
        // something they would think to read first.
        if (candidate.isSuppressed) return

        val notification = buildCandidate(candidate)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(InboxNotifications.notificationId(pendingId), notification)
            updateGroupSummary()
        }.onFailure { Log.w(TAG, "Could not post the inbox notification.", it) }
    }

    override suspend fun cancelCandidate(pendingId: String) {
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(InboxNotifications.notificationId(pendingId))
            updateGroupSummary()
        }.onFailure { Log.w(TAG, "Could not cancel the inbox notification.", it) }
    }

    /**
     * The grant, checked rather than assumed.
     *
     * **The API-level guard is not defensive noise; without it this method
     * returns false on every device below 33.** `POST_NOTIFICATIONS` became a
     * runtime permission in Tiramisu. On an older platform it is a string the
     * permission manager has never heard of, so `checkSelfPermission` answers
     * `DENIED` no matter what the manifest says — and `minSdk` here is 26. The
     * notification would simply never post on a third of the supported range,
     * silently, on the one path whose entire job is to stop things happening
     * silently.
     *
     * `areNotificationsEnabled` catches the other half — the app or the channel
     * muted in system settings — which is not a permission at all and applies at
     * every API level.
     */
    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private suspend fun buildCandidate(candidate: PendingTransaction): android.app.Notification {
        val currency = candidate.extracted.currency ?: baseCurrency()
        val title = titleOf(candidate, currency)
        val detail = detailOf(candidate)

        val builder = NotificationCompat.Builder(context, InboxNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lf_inbox_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$detail\n$REVERSIBILITY_NOTE"),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(InboxNotifications.GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(reviewIntent(candidate.id))
            // The lock screen sees the public version below and nothing else.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(discreetVersion())

        // §7d, and the reason it is not offered on every row: an amount and a
        // book are the two things the ledger cannot be given without, and
        // `isOneTapApprovable` is exactly that test. A candidate that needs a
        // manual fill has nothing to approve *from*, so offering the action
        // would be offering a button that can only fail.
        if (candidate.isOneTapApprovable) {
            builder.addAction(broadcastAction(APPROVE, "Approve", candidate.id))
        }
        // `[Review]` opens the Activity DIRECTLY -- the same PendingIntent the
        // body tap uses, not a broadcast. Routing it through InboxActionReceiver
        // would put a process hop in front of a deep link for no purpose, and
        // the receiver deliberately handles only the two actions that WRITE.
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Review", reviewIntent(candidate.id)).build(),
        )
        builder.addAction(broadcastAction(DISCARD, "Discard", candidate.id))

        return builder.build()
    }

    /**
     * §5.1's "grouped notification when >3 pending".
     *
     * Counted from what is actually in the shade rather than from
     * `observePendingCount()`, because the shade is the thing being grouped: a
     * user who dismissed four notifications has four pending candidates and an
     * empty shade, and posting a summary over nothing would be a notification
     * about notifications that are not there.
     */
    private fun updateGroupSummary() {
        val manager = NotificationManagerCompat.from(context)
        val posted = activeCandidateCount()

        if (posted > InboxNotifications.GROUP_THRESHOLD) {
            val summary = NotificationCompat.Builder(context, InboxNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lf_inbox_notification)
                .setContentTitle("$posted payments to review")
                .setGroup(InboxNotifications.GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(inboxIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(discreetVersion())
                .build()
            manager.notify(InboxNotifications.SUMMARY_ID, summary)
        } else {
            // Below the threshold the individual notifications read better on
            // their own, and a summary left behind after the fourth is reviewed
            // would be a bundle wrapped around three visible items.
            manager.cancel(InboxNotifications.SUMMARY_ID)
        }
    }

    private fun activeCandidateCount(): Int = runCatching {
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .count { it.groupKey?.contains(InboxNotifications.GROUP_KEY) == true &&
                it.id != InboxNotifications.SUMMARY_ID }
    }.getOrDefault(0)

    /**
     * What a locked device is allowed to show.
     *
     * No amount, no merchant, no bank — the fields a bank message carries are
     * exactly the ones §5.2 exists to keep private, and a lock screen is the one
     * surface that shows them to whoever is holding the phone.
     */
    private fun discreetVersion(): android.app.Notification =
        NotificationCompat.Builder(context, InboxNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lf_inbox_notification)
            .setContentTitle("A payment is waiting")
            .setContentText("Unlock to review it.")
            .setGroup(InboxNotifications.GROUP_KEY)
            .build()

    private fun titleOf(candidate: PendingTransaction, currency: String): String {
        val amount = candidate.extracted.amount
            ?: return "A message needs your attention"
        val figure = MoneyFormat.symbolised(amount.minor, currency)
        return when (candidate.extracted.direction) {
            ExtractedDirection.DEBIT -> "$figure spent"
            ExtractedDirection.CREDIT -> "$figure received"
            // §5.1 never guesses a book, and neither does this: the review
            // screen is where the user says which it was.
            ExtractedDirection.UNKNOWN -> figure
        }
    }

    private fun detailOf(candidate: PendingTransaction): String = when {
        candidate.needsManualFill -> "Tap to enter it by hand."
        candidate.extracted.merchantRaw != null -> requireNotNull(candidate.extracted.merchantRaw)
        else -> "Tap to review it."
    }

    private suspend fun baseCurrency(): String =
        // The vault is open here -- this runs straight after the write that
        // created the candidate -- but `baseCurrency()` takes `requireDatabase()`
        // and a throw on this path would cost the user the notification for a
        // row that was written perfectly well.
        runCatching { ledgerRepository.baseCurrency() }.getOrNull() ?: DEFAULT_CURRENCY

    /** §5.1's deep link. Constrained to this app, and never `BROWSABLE`. */
    private fun reviewIntent(pendingId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$DEEP_LINK_PREFIX$pendingId"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            "$REVIEW_ACTION:$pendingId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The summary opens the queue rather than any one candidate. */
    private fun inboxIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(INBOX_LINK))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            INBOX_LINK.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * One of the two actions that **write**, aimed at [InboxActionReceiver] by
     * class rather than by filter.
     *
     * Explicit, so nothing outside this app can receive it and the receiver
     * needs no `exported` surface at all. `[Review]` does not come through here:
     * it opens the Activity directly.
     */
    private fun broadcastAction(
        action: String,
        label: String,
        pendingId: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, InboxActionReceiver::class.java)
            .setAction(action)
            .putExtra(InboxActionReceiver.EXTRA_PENDING_ID, pendingId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "$action:$pendingId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }

    internal companion object {
        const val APPROVE: String = "com.ledgerflow.inbox.APPROVE"
        const val REVIEW_ACTION: String = "com.ledgerflow.inbox.REVIEW"
        const val DISCARD: String = "com.ledgerflow.inbox.DISCARD"

        /** §5.1's `ledgerflow://inbox/{pendingId}`. */
        const val DEEP_LINK_PREFIX: String = "ledgerflow://inbox/"
        const val INBOX_LINK: String = "ledgerflow://inbox"

        /**
         * §7c, as the owner chose to answer it: the shade has no snackbar, so
         * the reversibility is stated rather than offered. §5.1 keeps a
         * discarded row for 30 days and the Inbox's Discarded filter restores
         * it; without this line the user has no way to learn either.
         */
        const val REVERSIBILITY_NOTE: String =
            "Discarded payments stay in the Inbox for 30 days."

        const val DEFAULT_CURRENCY: String = "INR"
        private const val TAG = "InboxNotifier"
    }
}
