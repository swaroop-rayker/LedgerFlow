package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC.md §6.1.1's denormalisation invariant, asserted over the stored rows.
 *
 * `ledger_entry` carries both `category_id` and `subcategory_id` so analytics
 * can group by category without a self-join against `category`. The cost is
 * that the two columns can disagree, and SQLite cannot stop them: a `CHECK`
 * constraint may not contain a subquery, so there is no way to express "the
 * subcategory's parent equals the category" in the schema.
 *
 * That leaves exactly two things standing between the ledger and a bucket that
 * silently does not add up: `ApproveTransactionUseCase` refusing to write such
 * a row, and this test proving that nothing did.
 *
 * The second test here matters as much as the first. A scan that cannot fail is
 * a scan that gets trusted, and the first version of `LedgerIsolationTest`
 * passed vacuously for exactly that reason — so this one forces a violation in
 * through the DAO and asserts the detector sees it.
 */
@RunWith(AndroidJUnit4::class)
class LedgerEntryConsistencyTest {

    private val vault = LedgerTestVault("lf_consistency_test")

    private lateinit var groceries: Category
    private lateinit var vegetables: Category
    private lateinit var transport: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        vegetables = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Vegetables", parentId = groceries.id)).success()
        transport = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Transport")).success()
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun approvedEntries_neverViolateTheInvariant() = runBlocking<Unit> {
        vault.ledger.approve(request(EntryAssignment(categoryId = groceries.id))).assertApproved()
        vault.ledger.approve(
            request(EntryAssignment(categoryId = groceries.id, subcategoryId = vegetables.id)),
        ).assertApproved()
        vault.ledger.approve(request(EntryAssignment(categoryId = transport.id))).assertApproved()
        vault.ledger.approve(request(EntryAssignment())).assertApproved()

        // Every attempt that would have broken it was refused.
        vault.ledger.approve(
            request(EntryAssignment(categoryId = transport.id, subcategoryId = vegetables.id)),
        )
        vault.ledger.approve(request(EntryAssignment(subcategoryId = vegetables.id)))

        assertNoInconsistencies()
    }

    /**
     * Proves the detector is not vacuous.
     *
     * The bad row goes in through the DAO, deliberately bypassing the single
     * writer — which is the only way to produce it, and is precisely why Law 1
     * has a guard test of its own.
     */
    @Test
    fun theScan_detectsAViolationForcedInThroughTheDao() = runBlocking<Unit> {
        val dao = vault.session.requireDatabase().ledgerEntryDao()
        dao.insertEntry(
            LedgerEntryEntity(
                id = vault.ids.generate(),
                ledger = LedgerType.DEBIT,
                amountMinor = Money(500L),
                currency = LedgerTestVault.BASE_CURRENCY,
                originalAmountMinor = null,
                originalCurrency = null,
                fxRateMicro = null,
                occurredAt = OCCURRED_AT,
                localDate = 0,
                merchantId = null,
                // "Vegetables" is a child of Groceries, not of Transport.
                categoryId = transport.id,
                subcategoryId = vegetables.id,
                paymentMethodId = null,
                note = null,
                source = EntrySource.IMPORT,
                sourceRefId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertThat(dao.inconsistentSubcategoryCount(LedgerType.DEBIT)).isEqualTo(1)
    }

    /** A subcategory recorded with no category is a mismatch, not a null to ignore. */
    @Test
    fun theScan_treatsAMissingCategoryAsAViolation() = runBlocking<Unit> {
        val dao = vault.session.requireDatabase().ledgerEntryDao()
        dao.insertEntry(
            LedgerEntryEntity(
                id = vault.ids.generate(),
                ledger = LedgerType.DEBIT,
                amountMinor = Money(500L),
                currency = LedgerTestVault.BASE_CURRENCY,
                originalAmountMinor = null,
                originalCurrency = null,
                fxRateMicro = null,
                occurredAt = OCCURRED_AT,
                localDate = 0,
                merchantId = null,
                categoryId = null,
                subcategoryId = vegetables.id,
                paymentMethodId = null,
                note = null,
                source = EntrySource.IMPORT,
                sourceRefId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertThat(dao.inconsistentSubcategoryCount(LedgerType.DEBIT)).isEqualTo(1)
    }

    /**
     * The other way rows go stale: a category is deleted and its entries move.
     *
     * `reassignCategory` clears `subcategory_id` in the same statement for this
     * exact reason — leaving the old subcategory behind under a new parent
     * breaks the invariant, and it is the kind of inconsistency that surfaces
     * months later as an analytics bucket that does not add up.
     */
    @Test
    fun reassigningACategory_leavesNoInconsistentRows() = runBlocking<Unit> {
        vault.ledger.approve(
            request(EntryAssignment(categoryId = groceries.id, subcategoryId = vegetables.id)),
        ).assertApproved()

        vault.categories.delete(groceries.id, reassignTo = transport.id)

        assertNoInconsistencies()
    }

    private suspend fun assertNoInconsistencies() {
        val dao = vault.session.requireDatabase().ledgerEntryDao()
        LedgerType.entries.forEach { ledger ->
            assertThat(dao.inconsistentSubcategoryCount(ledger)).isEqualTo(0)
        }
    }

    private fun request(assignment: EntryAssignment) = ApprovalRequest(
        ledger = LedgerType.DEBIT,
        amount = Money(100_00L),
        occurredAt = OCCURRED_AT,
        assignment = assignment,
    )

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private fun LedgerResult<*>.assertApproved() {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
    }

    private companion object {
        private const val OCCURRED_AT = 1_700_000_000_000L
    }
}
