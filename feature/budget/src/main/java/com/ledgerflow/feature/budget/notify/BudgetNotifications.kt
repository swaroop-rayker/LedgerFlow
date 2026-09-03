package com.ledgerflow.feature.budget.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
     * Posts one alert. Silently does nothing without the runtime permission.
     *
     * Checked rather than assumed: on API 33+ `POST_NOTIFICATIONS` is a runtime
     * grant the user may have refused, and `notify` throws `SecurityException`
     * without it. A budget alert is not worth crashing a background worker for.
     */
    public fun post(context: Context, alert: BudgetAlert, currency: String) {
        if (!canPost(context)) return

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

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private const val FULL = 100
}
