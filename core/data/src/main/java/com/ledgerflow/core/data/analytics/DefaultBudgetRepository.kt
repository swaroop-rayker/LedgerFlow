package com.ledgerflow.core.data.analytics

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.BudgetEntity
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetError
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.model.Money
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Budget CRUD over Room (`SPEC.md` §5.7).
 *
 * **Validation runs inside the transaction, for the reason
 * `DefaultLedgerRepository` gives about approval**: the invariants here are
 * statements about rows in *another* table — that the category exists and is
 * not hidden — and a check made before the transaction opens can be invalidated
 * by a soft-delete landing between the check and the insert. The budget would
 * then point at a category no picker will offer again, and nothing would report
 * it.
 *
 * **Refusals are returned, not thrown.** A category that vanished mid-form is a
 * sentence the user reads (`CLAUDE.md` §5).
 */
@Singleton
public class DefaultBudgetRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BudgetRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<Budget>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.budgetDao() ?: return@flatMapLatest flowOf(emptyList())
            dao.observeLive().map { rows -> rows.map { it.toDomainBudget() } }
        }

    override suspend fun create(request: NewBudget): BudgetResult<Budget> = withContext(io) {
        if (request.amount.minor <= 0L) {
            return@withContext BudgetResult.Failure(BudgetError.AmountNotPositive)
        }

        val database = session.requireDatabase()
        database.withTransaction {
            val category = database.categoryDao().byId(request.categoryId)
                ?: return@withTransaction BudgetResult.Failure(
                    BudgetError.CategoryNotFound(request.categoryId),
                )
            // `deleted_at` is NOT NULL DEFAULT 0 on `category` (§6.1.1), so a
            // hidden row is any non-zero timestamp rather than a null.
            if (category.deletedAt != 0L) {
                return@withTransaction BudgetResult.Failure(
                    BudgetError.CategoryHidden(request.categoryId),
                )
            }
            if (database.budgetDao().exists(request.categoryId, request.subcategoryId)) {
                return@withTransaction BudgetResult.Failure(
                    BudgetError.AlreadyBudgeted(request.categoryId),
                )
            }

            val entity = BudgetEntity(
                id = ids.generate(),
                categoryId = request.categoryId,
                subcategoryId = request.subcategoryId,
                period = request.period,
                amountMinor = request.amount,
                startDate = request.startDate,
                rolloverEnabled = request.rolloverEnabled,
                alertThresholds = request.alertThresholds.joinToString(","),
            )
            database.budgetDao().insert(entity)
            BudgetResult.Success(entity.toDomainBudget())
        }
    }

    override suspend fun updateAmount(id: String, amount: Money): BudgetResult<Unit> =
        withContext(io) {
            if (amount.minor <= 0L) {
                return@withContext BudgetResult.Failure(BudgetError.AmountNotPositive)
            }
            val affected = session.requireDatabase().budgetDao().updateAmount(id, amount.minor)
            // Zero rows means "already deleted" or "no such budget", and the
            // statement cannot tell them apart -- which is the point, the same
            // way `softDeleteEntry` handles it.
            if (affected == 0) {
                BudgetResult.Failure(BudgetError.CategoryNotFound(id))
            } else {
                BudgetResult.Success(Unit)
            }
        }

    override suspend fun recordAlert(id: String, threshold: Int, periodStart: Int) {
        withContext(io) {
            // `openForBackgroundWork()` rather than `requireDatabase()`: the
            // alert evaluation runs in a Worker with no Activity alive, where
            // `requireDatabase()` throws -- and that throw lands in a
            // `runCatching` and comes back as a clean success that recorded
            // nothing, which is BUG13's exact shape (CLAUDE.md §7).
            session.openForBackgroundWork()?.budgetDao()
                ?.recordAlert(id, threshold, periodStart)
        }
    }

    override suspend fun delete(id: String): BudgetResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().budgetDao()
            .softDelete(id, clock.nowMillis())
        if (affected == 0) {
            BudgetResult.Failure(BudgetError.CategoryNotFound(id))
        } else {
            BudgetResult.Success(Unit)
        }
    }
}
