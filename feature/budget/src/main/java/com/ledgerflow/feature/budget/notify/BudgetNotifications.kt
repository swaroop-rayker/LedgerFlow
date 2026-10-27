package com.ledgerflow.feature.budget.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.common.notify.NotificationPostPolicy
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.usecase.BudgetAlert
import java.time.LocalDate

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
            description = "When spending crosses a limit you set, or is on course to."
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

        val (title, text) = wordsFor(alert, currency)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // BUG75: a tap did nothing -- the alert had no content intent, so
            // it could only be swiped away. It opens Budgets, where the limit
            // it is about can be read and changed. Package-explicit, like the
            // Inbox's links, so only this app resolves it.
            .setContentIntent(openBudgets(context))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(alert.progress.budget.id.hashCode(), notification)
        }
    }

    /**
     * A notification's title and line.
     *
     * A threshold gives both figures, because a percentage alone hides the
     * scale and the headroom left is the thing the user can act on. An
     * overspending warning (V2-PLAN §3.4) says where this pace ends and by
     * when, against the limit, and calls it a pace -- it is a projection, and
     * a user who front-loads a month's shopping is not really on course for
     * thirty times that.
     */
    internal fun wordsFor(alert: BudgetAlert, currency: String): Pair<String, String> {
        val progress = alert.progress
        val name = progress.categoryName
        val limit = MoneyFormat.symbolised(progress.effectiveAmount.minor, currency)
        return when (alert) {
            is BudgetAlert.ThresholdCrossed -> {
                val spent = MoneyFormat.symbolised(progress.spent.minor, currency)
                val title = if (alert.threshold >= FULL) "$name budget used up" else "$name at ${alert.threshold}%"
                title to "$spent of $limit"
            }
            is BudgetAlert.OnCourseToOverrun -> {
                val projected = MoneyFormat.symbolised(progress.projectedSpend.minor, currency)
                val end = DisplayDates.dayMonth(LocalDate.ofEpochDay(progress.periodEnd.toLong()))
                "$name on course to go over" to "At this pace, $projected by $end. Limit $limit."
            }
        }
    }

    /** What Settings > Notifications > Budget alerts reports (V2-PLAN §3.4). */
    public fun channelState(context: Context): AlertChannelState {
        if (!NotificationPostPolicy.isPermitted(context)) return AlertChannelState.APP_OFF
        val channel = NotificationManagerCompat.from(context).getNotificationChannelCompat(CHANNEL_ID)
        return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            AlertChannelState.CHANNEL_OFF
        } else {
            AlertChannelState.ON
        }
    }

    /**
     * Android's own page for this channel, or the app's notification page when
     * all of LedgerFlow's notifications are off -- the channel page cannot turn
     * those back on.
     */
    public fun settingsIntent(context: Context, state: AlertChannelState): Intent = when (state) {
        AlertChannelState.APP_OFF -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        AlertChannelState.ON, AlertChannelState.CHANNEL_OFF ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
    }.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** `ledgerflow://budgets`; `:app`'s `BudgetsDeepLink` routes it. */
    public const val BUDGETS_LINK: String = "ledgerflow://budgets"

    private fun openBudgets(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BUDGETS_LINK))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            BUDGETS_LINK.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val FULL = 100
}
