package com.ledgerflow.feature.budget.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.EvaluateBudgetAlertsUseCase
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * §5.7's threshold alerts.
 *
 * **A one-shot enqueued after an approval, not a periodic sweep.** A budget can
 * only cross a threshold when spending changes, and spending only changes when
 * a ledger write commits — so a daily poll would either be late (the user
 * learns tomorrow that they went over today) or wasteful (checking on days
 * nothing happened). `ExistingWorkPolicy.KEEP` collapses a burst of approvals
 * into one evaluation, which is what the user wants anyway: three purchases in
 * a minute is one crossing, not three.
 *
 * **It runs with no Activity alive**, which is the case `CLAUDE.md` §7 is
 * emphatic about — both the analytics read and `recordAlert` open the vault
 * through `openForBackgroundWork()`, or this would report success having read
 * nothing and announced nothing.
 *
 * **Never throws** (`CLAUDE.md` §8, BUG7(e)). A worker that throws is a crash
 * in a background process.
 */
@HiltWorker
public class BudgetAlertWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val evaluate: EvaluateBudgetAlertsUseCase,
    private val ledgerRepository: LedgerRepository,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY
        val alerts = evaluate(LocalDates.of(clock.nowMillis()))
        alerts.forEach { alert -> BudgetNotifications.post(appContext, alert, currency) }
        Result.success()
    }.getOrElse { error ->
        Log.e(TAG, "Budget alert evaluation failed", error)
        Result.retry()
    }

    public companion object {
        private const val TAG = "BudgetAlertWorker"
        private const val UNIQUE_NAME = "lf-budget-alerts"
        private const val DEFAULT_CURRENCY = "INR"

        /**
         * Evaluate once, soon.
         *
         * `KEEP` rather than `REPLACE`: a burst of approvals should produce one
         * evaluation, and replacing would restart the timer on each, so a user
         * filing several receipts in a row would be the one person who never
         * gets an alert.
         */
        public fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BudgetAlertWorker>().build(),
            )
        }
    }
}
