package com.ledgerflow.feature.ingest.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.inbox.InboxNotifier
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.feature.ingest.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * §5.1's `[Approve] [Discard]` actions, handled from the shade. P2-7.
 *
 * **Law 1 is not weakened by this class.** `[Approve]` calls
 * [ApprovePendingUseCase], which composes `ApproveTransactionUseCase` — the
 * single writer — exactly as the review screen does. `LedgerSingleWriterTest`
 * guards that door and this is deliberately not a fifth one. What a shade tap
 * buys is a shorter path to the same human decision, not a path around it.
 *
 * **It runs with no Activity alive, which is the whole point and was the whole
 * bug.** Until BUG13, every one of these calls reached `requireDatabase()` on a
 * vault only `AppViewModel` ever opened; the throw was swallowed and the action
 * returned a clean `false`. `DefaultPendingRepository` opens the vault for
 * itself now, and `Bug13_ShadeActionOnClosedVaultTest` is what keeps that true.
 *
 * **`[Review]` is not handled here.** It is a `PendingIntent.getActivity` on the
 * notification's own action, so it goes straight to the deep link without a
 * broadcast in the middle — one fewer process hop, and it cannot be silently
 * dropped by a receiver that ran out of time.
 *
 * The receiver is **not exported**: every `PendingIntent` aimed at it names the
 * class explicitly, so nothing outside this app can reach it and no
 * `intent-filter` is needed.
 */
@AndroidEntryPoint
internal class InboxActionReceiver : BroadcastReceiver() {

    @Inject internal lateinit var discardPending: DiscardPendingUseCase

    @Inject internal lateinit var approvePending: ApprovePendingUseCase

    @Inject internal lateinit var notifier: InboxNotifier

    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher

    /**
     * No `super.onReceive` call, for the reason `SmsIngestReceiver` documents at
     * length: it is abstract, so it does not compile in Kotlin, and the Hilt
     * Gradle plugin injects the call into this method's bytecode afterwards.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getStringExtra(EXTRA_PENDING_ID) ?: return
        val action = intent.action ?: return
        if (action != AndroidInboxNotifier.APPROVE && action != AndroidInboxNotifier.DISCARD) return

        val pendingResult = goAsync()
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Inbox action $action failed.", throwable)
        }
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher + handler)
        scope.launch {
            try {
                // The notification goes first, whatever happens next: the user
                // has tapped it and leaving it in the shade while the write
                // runs reads as an action that did nothing. A failure re-posts
                // its own notification below.
                notifier.cancelCandidate(pendingId)
                when (action) {
                    AndroidInboxNotifier.APPROVE -> approve(context, pendingId)
                    AndroidInboxNotifier.DISCARD -> discard(context, pendingId)
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun approve(context: Context, pendingId: String) {
        approvePending(pendingId).onFailure { throwable ->
            Log.e(TAG, "Approving $pendingId from the shade failed.", throwable)
            // §5.1 forbids a financial message being *silently* dropped, and a
            // tap that quietly achieves nothing is the same failure wearing the
            // user's own gesture. The candidate is untouched and still in the
            // Inbox, so this says exactly that.
            postFailure(
                context,
                pendingId,
                title = "Could not approve that payment",
                text = "It is still waiting in your Inbox.",
            )
        }
    }

    private suspend fun discard(context: Context, pendingId: String) {
        if (!discardPending(pendingId)) {
            Log.e(TAG, "Discarding $pendingId from the shade failed.")
            postFailure(
                context,
                pendingId,
                title = "Could not discard that payment",
                text = "It is still waiting in your Inbox.",
            )
        }
    }

    /**
     * Tell the user their tap did not land.
     *
     * **This does not close §16 Q16**, and should not be mistaken for it. Q16 is
     * about a message *arriving* when the vault cannot open, which needs storage
     * outside the vault and a surface that survives the app never launching.
     * This is the much smaller case the owner is owed immediately: they tapped a
     * button a moment ago and are still holding the phone.
     */
    private fun postFailure(context: Context, pendingId: String, title: String, text: String) {
        runCatching {
            val notification = NotificationCompat.Builder(
                context,
                InboxNotifications.CHANNEL_ID,
            )
                .setSmallIcon(R.drawable.ic_lf_inbox_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                // Deliberately outside the candidate group: it is not a payment
                // to review, and bundling it under "N payments to review" would
                // make the count wrong as well as the meaning.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            NotificationManagerCompat.from(context).notify(
                failureNotificationId(pendingId),
                notification,
            )
        }.onFailure { Log.w(TAG, "Could not post the failure notification.", it) }
    }

    /** Its own slot, so it cannot evict the candidate's or the group summary's. */
    private fun failureNotificationId(pendingId: String): Int =
        "failure:$pendingId".hashCode()

    internal companion object {
        const val EXTRA_PENDING_ID: String = "com.ledgerflow.inbox.PENDING_ID"
        private const val TAG = "InboxAction"
    }
}
