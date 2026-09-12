package com.ledgerflow.feature.budget.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.common.notify.NotificationPostPolicy
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.usecase.BudgetAlert

/**
 * §5.7's threshold notifications.
 *
 * **Its own channel, at DEFAULT importance — deliberately quieter than the
 * Inbox's.** `inbox_high` is HIGH because an unfiled payment is something only
 * the user can resolve and it decays if ignored. A budget crossing 80% is
 * information: it does not need to interrupt, and giving it the same weight as
 * a capture would train people to dismiss both. A separate channel also means
 * the user can mute budget alerts without muting capture, which they cannot do
 * if the two share one.
 *
 * **One notification per budget, keyed by budget id.** Two budgets crossing on
 * the same day are two separate facts about two separate categories; collapsing
 * them into one line would mean neither is actionable. Re-using the id means a
 * later crossing on the same budget *replaces* rather than stacks.
 */
public object BudgetNotifications {

    internal const val CHANNEL_ID: String = "budget_alerts"

    public fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Budget alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "When spending crosses a limit you set."
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * Posts one alert. Silently does nothing when the app may not post.
     *
     * Checked rather than assumed, through
     * [com.ledgerflow.core.common.notify.NotificationPostPolicy] — which is
     * shared with `:feature:ingest` because the local copy this used to hold
     * had **lost the API-level guard**, so no budget alert could post below API
     * 33 at all. The platform drops an ungranted post rather than throwing, so
     * nothing reported it: an alert that never appears and never fails is
     * indistinguishable from a budget that was never crossed.
     */
    @Suppress("MissingPermission") // NotificationPostPolicy is the check; see its note.
    public fun post(context: Context, alert: BudgetAlert, currency: String) {
        if (!NotificationPostPolicy.isPermitted(context)) return

        val name = alert.progress.categoryName
        val spent = MoneyFormat.symbolised(alert.progress.spent.minor, currency)
        val limit = MoneyFormat.symbolised(alert.progress.effectiveAmount.minor, currency)

        val title = if (alert.threshold >= FULL) {
            "$name budget used up"
        } else {
            "$name at ${alert.threshold}%"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            // Both figures, because a percentage alone hides the scale and the
            // headroom left is the thing the user can act on.
            .setContentText("$spent of $limit")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(alert.progress.budget.id.hashCode(), notification)
        }
    }

    private const val FULL = 100
}
