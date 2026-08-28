package com.ledgerflow.feature.ingest.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The channel, the group, and the ids §5.1's notification is posted with. P2-7.
 *
 * Constants and one idempotent setup call, deliberately with no dependencies:
 * `LedgerFlowApplication` calls [ensureChannel] at startup, and the same call
 * has to be safe from a `BroadcastReceiver` process that woke with no Activity.
 */
public object InboxNotifications {

    /**
     * §5.1 names this channel. **Importance HIGH, no sound by default.**
     *
     * The two are not in tension. Importance decides whether the notification
     * gets to interrupt — a payment the user has not filed is worth a heads-up —
     * and the sound is a separate axis the user owns. Defaulting a personal
     * finance app to *audible* on every UPI payment is how an app gets muted
     * wholesale, which costs the user every future notification too.
     *
     * The literal id §5.1 specifies, and a one-way door: a channel's importance
     * and sound are **not updatable after creation**. Android hands both to the
     * user the moment the channel exists, and `createNotificationChannel` on an
     * existing id silently ignores any later opinion of ours — so the settings
     * below take effect on a fresh install and never on an upgrade. Changing
     * either afterwards means creating a *new* id and deleting this one, which
     * also discards whatever the user had chosen. Do not treat the values below
     * as editable defaults; they are the ones every existing install already has.
     */
    internal const val CHANNEL_ID: String = "inbox_high"

    /** §5.1's grouping: the bundle every candidate notification joins. */
    internal const val GROUP_KEY: String = "com.ledgerflow.inbox"

    /**
     * §5.1's "grouped notification when >3 pending".
     *
     * A summary is posted once the shade holds more than this many candidates.
     * Below it, individual notifications read better — a bundle of two is a
     * bundle the user has to open to learn anything.
     */
    internal const val GROUP_THRESHOLD: Int = 3

    /** The summary's own id. Fixed: there is at most one, and it is replaced in place. */
    internal const val SUMMARY_ID: Int = 1

    /**
     * Created once per process, not once per post.
     *
     * `createNotificationChannel` is already idempotent — the platform ignores a
     * repeat for an existing id — so this flag is not for correctness. It is for
     * the binder round-trip, which would otherwise run on every captured message
     * on a path that also has a `BroadcastReceiver`'s ten seconds to respect.
     */
    private val channelReady = AtomicBoolean(false)

    /**
     * Make sure the channel exists.
     *
     * Called from `LedgerFlowApplication.onCreate` rather than lazily before the
     * first post, so the user can find and configure it in system settings
     * *before* the first bank message arrives rather than after. `onCreate` runs
     * for every process entry, a receiver-only wake included, so the background
     * path is covered by the same call.
     */
    public fun ensureChannel(context: Context) {
        if (!channelReady.compareAndSet(false, true)) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Inbox",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A captured payment is waiting for you to approve or discard it."
            // The "no sound by default" half of §5.1. The user can turn it on in
            // system settings, and once they have, this code never overrides it
            // -- the platform stops accepting our opinion after creation.
            setSound(null, null)
            enableVibration(false)
            // The Inbox count lives on §9.3's speed dial, which is a real number
            // the user can act on. A launcher dot that says "something" adds
            // nothing to it.
            setShowBadge(false)
        }

        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * A stable notification id for one candidate.
     *
     * Derived from the id rather than counted, so re-posting the same candidate
     * *replaces* its notification instead of stacking a second one — the parse
     * pass is idempotent and re-runs routinely, and the user must not collect a
     * notification per run.
     *
     * `hashCode` can collide, and the consequence is bounded and acceptable: two
     * candidates would share a slot, so the newer replaces the older in the
     * shade. Neither row is touched, both are still in the Inbox, and nothing is
     * dropped — §5.1's never-drop rule is about the database, and this is only
     * the announcement. [SUMMARY_ID] is excluded so a collision cannot evict the
     * group summary.
     */
    internal fun notificationId(pendingId: String): Int {
        val hash = pendingId.hashCode()
        return if (hash == SUMMARY_ID) SUMMARY_ID + 1 else hash
    }
}
