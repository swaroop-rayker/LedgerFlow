package com.ledgerflow.feature.tools.recurring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.common.notify.NotificationPostPolicy
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.feature.tools.R
import java.time.LocalDate

/**
 * Recurring items' reminders (V2-PLAN §4.2, §3.4; owner 2026-11-09).
 *
 * **Their own channel, at DEFAULT importance**, as budget alerts have: a
 * reminder is information, quieter than the Inbox's HIGH, and the user can
 * mute it without muting capture. Android's channel is the only switch;
 * Settings > Notifications reports it.
 *
 * **One notification per item**, keyed by its id: the next due date's
 * reminder replaces the last one rather than stacking.
 */
public object RecurringNotifications {

    internal const val CHANNEL_ID: String = "reminders"

    public fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Before a recurring payment you set up is due."
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Posts one reminder. Does nothing when the app may not post (`NotificationPostPolicy`). */
    @Suppress("MissingPermission") // NotificationPostPolicy is the check.
    public fun post(context: Context, item: RecurringItem, dueDate: Int, today: Int, currency: String) {
        if (!NotificationPostPolicy.isPermitted(context)) return
        val (title, text) = wordsFor(item, dueDate, today, currency)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lf_reminder_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification) }
    }

    /** "Rent due tomorrow" and "₹25,000.00, due 1 Dec"; "about" when the amount varies. */
    internal fun wordsFor(item: RecurringItem, dueDate: Int, today: Int, currency: String): Pair<String, String> {
        val title = when (val days = dueDate - today) {
            0 -> "${item.name} due today"
            1 -> "${item.name} due tomorrow"
            else -> "${item.name} due in $days days"
        }
        val amount = MoneyFormat.symbolised(item.amount.minor, currency)
        val day = DisplayDates.dayMonth(LocalDate.ofEpochDay(dueDate.toLong()))
        return title to (if (item.amountVaries) "About $amount, due $day" else "$amount, due $day")
    }

    /** What Settings > Notifications > Reminders reports. */
    public fun channelState(context: Context): AlertChannelState {
        if (!NotificationPostPolicy.isPermitted(context)) return AlertChannelState.APP_OFF
        val channel = NotificationManagerCompat.from(context).getNotificationChannelCompat(CHANNEL_ID)
        return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            AlertChannelState.CHANNEL_OFF
        } else {
            AlertChannelState.ON
        }
    }

    /** Android's page for this channel, or the app's when all its notifications are off. */
    public fun settingsIntent(context: Context, state: AlertChannelState): Intent = when (state) {
        AlertChannelState.APP_OFF -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        AlertChannelState.ON, AlertChannelState.CHANNEL_OFF ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
    }.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** The app as it opens; the recurring page arrives with the screens PR. */
    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            CHANNEL_ID.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
