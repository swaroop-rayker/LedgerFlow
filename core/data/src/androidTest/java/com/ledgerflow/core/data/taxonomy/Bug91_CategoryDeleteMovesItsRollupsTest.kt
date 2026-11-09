package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG91: deleting a category and moving its entries left `daily_rollup` filing
 * that money under the deleted category until the nightly reconciliation, so
 * Analytics, budgets and savings goals read it there, or nowhere. Found
 * 2026-11-09 by `GoalRepositoryTest` on the SM-S721B: a goal followed its
 * category's entries and its total read nothing.
 *
 * The rollup is re-summed over the days the move touched, in the delete's own
 * transaction, as a merchant merge does (BUG82). Red before the fix: the
 * target read 0 and the deleted category kept the money.
 */
@RunWith(AndroidJUnit4::class)
class Bug91_CategoryDeleteMovesItsRollupsTest {

    private val vault = LedgerTestVault("lf_bug91_test")

    private lateinit var savings: Category
    private lateinit var deposits: Category
    private lateinit var investments: Category

    private val day = LocalDate.of(2026, 9, 1).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        savings = vault.categories.create(NewCategory(LedgerType.DEBIT, "Savings")).success()
        deposits = vault.categories.create(NewCategory(LedgerType.DEBIT, "Deposits", parentId = savings.id)).success()
        investments = vault.categories.create(NewCategory(LedgerType.DEBIT, "Investments")).success()
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun deletingACategory_movesItsMoneyInTheRollups_atOnce() = runBlocking<Unit> {
        approve(2_000_00, savings.id, null, day)
        approve(3_000_00, savings.id, deposits.id, day + 30)

        vault.categories.delete(savings.id, reassignTo = investments.id).success()

        assertThat(spend(investments.id)).isEqualTo(5_000_00L)
        assertThat(spend(savings.id)).isEqualTo(0L)
    }

    @Test
    fun purgingAHiddenCategory_movesItsMoneyInTheRollups_atOnce() = runBlocking<Unit> {
        approve(2_000_00, savings.id, null, day)
        // Hidden by moving nothing: its entry is binned first, so the delete
        // needs no target, and the purge is what moves it.
        val entry = vault.database.ledgerEntryDao().allForLedger(LedgerType.DEBIT).single().id
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry)
        vault.categories.delete(savings.id, reassignTo = null).success()
        vault.ledger.restoreEntry(LedgerType.DEBIT, entry)

        vault.categories.purge(savings.id, reassignTo = investments.id).success()

        assertThat(spend(investments.id)).isEqualTo(2_000_00L)
        assertThat(spend(savings.id)).isEqualTo(0L)
    }

    private suspend fun spend(categoryId: String): Long =
        vault.database.dailyRollupDao().categorySpend(categoryId, day - 10, day + 60)

    private suspend fun approve(amount: Long, categoryId: String, subcategoryId: String?, on: Int) {
        val occurredAt = LocalDate.ofEpochDay(on.toLong()).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(categoryId = categoryId, subcategoryId = subcategoryId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
