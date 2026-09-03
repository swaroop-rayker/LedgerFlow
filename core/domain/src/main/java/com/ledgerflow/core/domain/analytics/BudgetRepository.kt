package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * A budget the user is about to create (`SPEC.md` §5.7).
 *
 * No `ledger`: §5.7 scopes budgets to debit and §6.1 gives the table no such
 * column, so there is nothing to choose. `startDate` is days since epoch, and
 * the period repeats from it rather than snapping to a calendar
 * ([BudgetPeriods]).
 */
public data class NewBudget(
    val categoryId: String,
    val subcategoryId: String? = null,
    val period: BudgetPeriod,
    val amount: Money,
    val startDate: Int,
    val rolloverEnabled: Boolean = false,
    val alertThresholds: List<Int> = DEFAULT_ALERT_THRESHOLDS,
)

/** §5.7's defaults: warn at 80% of the budget, and again at 100%. */
public val DEFAULT_ALERT_THRESHOLDS: List<Int> = listOf(80, 100)

/**
 * The parts of a budget the user may change afterwards (`SPEC.md` §5.7, Q20).
 *
 * **Everything except identity.** `categoryId` and `subcategoryId` are what the
 * budget *is* — the repository enforces one live budget per pair, so changing
 * them is creating a different budget, not editing this one. The alert columns
 * are bookkeeping the user never sets.
 *
 * All four move in **one statement**. Amount and period decide the same
 * figure ("₹2,000 of what window"), and writing them separately leaves a moment
 * where a monthly amount is being read against a weekly window — brief, but a
 * `BudgetAlertWorker` running in it would announce a threshold against a budget
 * that never existed.
 */
public data class BudgetSettings(
    val amount: Money,
    val period: BudgetPeriod,
    val startDate: Int,
    val rolloverEnabled: Boolean,
)

/** Why a budget could not be saved. Returned, never thrown (`CLAUDE.md` §5). */
public sealed interface BudgetError {
    /** The category vanished between opening the form and saving it. */
    public data class CategoryNotFound(val id: String) : BudgetError

    /** §5.7 is per-*category*; a budget of zero is a deletion in disguise. */
    public data object AmountNotPositive : BudgetError

    /**
     * One live budget per category/subcategory pair.
     *
     * Two budgets on one category would each show a different "remaining", and
     * the app would have no basis for choosing which one to alert on. The
     * schema does not enforce it — §6.1 has no unique index here — so the rule
     * lives in the repository and is asserted by a test.
     */
    public data class AlreadyBudgeted(val categoryId: String) : BudgetError

    /** A category the user has hidden cannot take a new budget. */
    public data class CategoryHidden(val id: String) : BudgetError
}

public sealed interface BudgetResult<out T> {
    public data class Success<T>(val value: T) : BudgetResult<T>
    public data class Failure(val error: BudgetError) : BudgetResult<Nothing>
}

/**
 * Budget CRUD (`SPEC.md` §5.7).
 *
 * Separate from [AnalyticsRepository], which only *reads* budgets as part of a
 * snapshot. Keeping the write path on its own port is what stops a chart query
 * growing the ability to create one.
 */
public interface BudgetRepository {

    /** Live budgets, newest period first. Cold, so an edit shows up by itself. */
    public fun observeAll(): Flow<List<Budget>>

    public suspend fun create(request: NewBudget): BudgetResult<Budget>

    /**
     * Change a budget's amount, period, start date and rollover (Q20).
     *
     * **The current period is re-cut on the spot**, because a period is derived
     * from `startDate` and the period length rather than stored — there is no
     * "this period" record to preserve. So moving either one moves the window
     * the user is standing in, and their spend-so-far figure changes with no
     * transaction behind it. That is the honest reading of the edit: they asked
     * for a different window and they are shown it.
     *
     * **Alert state is deliberately left alone.** `thresholdToAnnounce`
     * suppresses on `alertPeriodStart == periodStart`, and re-cutting the
     * period changes `periodStart` — so a shape change re-announces a crossing
     * by itself, with no reset and no extra column. An edit that leaves the
     * boundaries where they were keeps the suppression, which is also right:
     * the same window should not announce the same threshold twice.
     */
    public suspend fun update(id: String, settings: BudgetSettings): BudgetResult<Unit>

    /**
     * Soft delete (`CLAUDE.md` §7's shape for user-authored rows).
     *
     * A budget is user intent that nothing can reconstruct (ADR-0006), and
     * ADR-0017 puts the bin in the backup — which is only true if deleting sets
     * a timestamp rather than removing the row.
     */
    public suspend fun delete(id: String): BudgetResult<Unit>

    /**
     * Mark a threshold as announced for a period (§5.7).
     *
     * Not a `BudgetResult`: this is bookkeeping the alert path performs on its
     * own behalf, and there is no user-facing failure to report — a budget
     * deleted between evaluation and recording simply affects no rows, which is
     * the correct outcome.
     */
    public suspend fun recordAlert(id: String, threshold: Int, periodStart: Int)
}
