package com.ledgerflow.feature.tools.recurring

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringReminders
import com.ledgerflow.core.domain.recurring.ReminderChannel
import com.ledgerflow.core.domain.usecase.RunRecurringPassUseCase
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Recurring items' daily pass (V2-PLAN §4.2; owner 2026-11-09: once a day,
 * around 09:00). It runs with no Activity alive, so the repository it drives
 * opens the vault itself (`CLAUDE.md` §7). A failure is retried; the pass is
 * idempotent, so a retry never reminds or adds twice.
 */
@HiltWorker
public class RecurringWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pass: RunRecurringPassUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val summary = pass()
        // Counts only, never a name or an amount.
        Log.i(TAG, "reminded=${summary.reminded} matched=${summary.matched} " +
            "candidates=${summary.candidates} skipped=${summary.skipped}")
        Result.success()
    }.getOrElse { error ->
        Log.e(TAG, "Recurring pass failed", error)
        Result.retry()
    }

    public companion object {
        private const val TAG = "RecurringWorker"
        private const val DAILY_NAME = "lf-recurring-daily"
        private const val SOON_NAME = "lf-recurring-soon"
        private val AROUND: LocalTime = LocalTime.of(9, 0)

        /**
         * Once a day, first at the next 09:00. `KEEP`, as the rollup pass
         * keeps its schedule: re-registering on every launch with `UPDATE`
         * would restart the period and could defer the pass for ever.
         * Android may move it by up to an hour, as it does the nightly backup.
         */
        public fun schedule(context: Context) {
            val now = LocalDateTime.now(ZoneId.systemDefault())
            val today = now.toLocalDate().atTime(AROUND)
            val next = if (now.isBefore(today)) today else today.plusDays(1)
            val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(DAILY_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One pass now, as the app opens: a phone that slept through 09:00 still hears today. */
        public fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecurringWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** [RecurringReminders] on the Reminders channel, in the install's base currency. */
internal class AndroidRecurringReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ledger: LedgerRepository,
    private val clock: Clock,
) : RecurringReminders {

    override suspend fun remind(item: RecurringItem, dueDate: Int) {
        val currency = ledger.baseCurrency() ?: DEFAULT_CURRENCY
        RecurringNotifications.post(context, item, dueDate, LocalDates.of(clock.nowMillis()), currency)
    }

    private companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}

internal class AndroidReminderChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderChannel {
    override fun state(): AlertChannelState = RecurringNotifications.channelState(context)
}

/** The recurring ports, bound beside their channel (as `:feature:ingest` binds the Inbox's). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecurringModule {

    @Binds
    internal abstract fun reminders(impl: AndroidRecurringReminders): RecurringReminders

    @Binds
    internal abstract fun channel(impl: AndroidReminderChannel): ReminderChannel
}
