package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.model.Money
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Live budgets, newest period first (SPEC.md §5.7). */
public class ObserveBudgetsUseCase @Inject constructor(
    private val budgets: BudgetRepository,
) {
    public operator fun invoke(): Flow<List<Budget>> = budgets.observeAll()
}

public class CreateBudgetUseCase @Inject constructor(
    private val budgets: BudgetRepository,
) {
    public suspend operator fun invoke(request: NewBudget): BudgetResult<Budget> =
        budgets.create(request)
}

public class UpdateBudgetAmountUseCase @Inject constructor(
    private val budgets: BudgetRepository,
) {
    public suspend operator fun invoke(id: String, amount: Money): BudgetResult<Unit> =
        budgets.updateAmount(id, amount)
}

/**
 * Soft delete, not destruction.
 *
 * A budget is user intent nothing can reconstruct (ADR-0006), and ADR-0017 puts
 * the bin in the backup -- which is only true if deleting sets a timestamp.
 */
public class DeleteBudgetUseCase @Inject constructor(
    private val budgets: BudgetRepository,
) {
    public suspend operator fun invoke(id: String): BudgetResult<Unit> = budgets.delete(id)
}
