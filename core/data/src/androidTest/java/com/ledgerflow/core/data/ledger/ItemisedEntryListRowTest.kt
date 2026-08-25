package com.ledgerflow.core.data.ledger

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How an itemised entry reads back in the two list surfaces (SPEC.md §5.5,
 * ADR-0018).
 *
 * An itemised entry stores no category on `ledger_entry` itself, so both the
 * Ledger list and the bin fall back to a correlated read of its line items.
 * They are **separate statements** -- `pagingDebits`/`pagingCredits` read the
 * per-book views, while the bin reads `ledger_entry` directly because the
 * views' predicate is `deleted_at IS NULL` -- so each carries its own copy of
 * the fallback and each needs its own coverage. Fixing the list first and
 * discovering the bin still said "Unfiled" is exactly how this file came to
 * cover both.
 *
 * Everything goes through the repository and `asSnapshot()` rather than
 * asserting on SQL: a raw-query test would keep passing if the repository
 * stopped calling these statements at all.
 */
@RunWith(AndroidJUnit4::class)
class ItemisedEntryListRowTest {

    private val vault = LedgerTestVault("lf_itemised_list_row_test")

    private lateinit var groceries: Category
    private lateinit var dairy: Category
    private lateinit var electronics: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = category(LedgerType.DEBIT, "Groceries")
        dairy = category(LedgerType.DEBIT, "Dairy", parentId = groceries.id)
        electronics = category(LedgerType.DEBIT, "Electronics")
    }

    @After
    fun tearDown() = vault.close()

    /**
     * The scenario the feature exists for. One payment, two categories, and the
     * list has to say so rather than falling into the same "nothing chosen"
     * bucket a genuinely uncategorised row would.
     */
    @Test
    fun itemisedEntry_showsItsLargestLineItemCategoryAndACount() = runBlocking<Unit> {
        approve(
            amount = 1_000_00L,
            lines = listOf(
                NewLineItem(name = "Weekly shop", total = Money(600_00L), categoryId = groceries.id),
                NewLineItem(name = "Kettle", total = Money(400_00L), categoryId = electronics.id),
            ),
        )

        val row = singleRow()
        assertThat(row.categoryName).isNull() // ADR-0018: the entry itself files nothing.
        assertThat(row.displayCategoryName).isEqualTo("Groceries")
        assertThat(row.displayCategoryColorArgb).isEqualTo(groceries.colorArgb)
        assertThat(row.additionalCategoryCount).isEqualTo(1)
    }

    /** One category, several lines: no "+" left to report. */
    @Test
    fun itemisedEntry_allOneCategory_showsNoCount() = runBlocking<Unit> {
        approve(
            amount = 300_00L,
            lines = listOf(
                NewLineItem(name = "Milk", total = Money(100_00L), categoryId = groceries.id, subcategoryId = dairy.id),
                NewLineItem(name = "Bread", total = Money(200_00L), categoryId = groceries.id),
            ),
        )

        val row = singleRow()
        assertThat(row.displayCategoryName).isEqualTo("Groceries")
        assertThat(row.additionalCategoryCount).isNull()
    }

    /** The larger line wins the swatch, not the first one entered. */
    @Test
    fun itemisedEntry_picksTheCategoryWithTheLargerTotal_notThePositionalFirst() = runBlocking<Unit> {
        approve(
            amount = 500_00L,
            lines = listOf(
                NewLineItem(name = "Charger", total = Money(150_00L), categoryId = electronics.id),
                NewLineItem(name = "Big shop", total = Money(350_00L), categoryId = groceries.id),
            ),
        )

        assertThat(singleRow().displayCategoryName).isEqualTo("Groceries")
    }

    /** No categorised lines at all: still genuinely unfiled, same as before ADR-0018. */
    @Test
    fun itemisedEntry_withNoCategorisedLines_hasNoDisplayCategory() = runBlocking<Unit> {
        approve(amount = 100_00L, lines = listOf(NewLineItem(name = "Misc", total = Money(100_00L))))

        val row = singleRow()
        assertThat(row.displayCategoryName).isNull()
        assertThat(row.additionalCategoryCount).isNull()
    }

    // ── The bin, which reads its own statement (ADR-0015, ADR-0018) ─────────

    /**
     * A binned itemised entry keeps its line-item filing.
     *
     * The bin reads `ledger_entry` directly rather than a view — the views'
     * predicate is `deleted_at IS NULL` — so it is a *separate* statement with
     * its own copy of the fallback. This is what proves the second one works;
     * the list's tests say nothing about it.
     *
     * It also proves the soft delete leaves the line items in place:
     * `line_item`'s `ON DELETE CASCADE` fires only on a real `DELETE`, which is
     * the same property restoring an entry depends on.
     */
    @Test
    fun binnedItemisedEntry_stillShowsItsLineItemCategory() = runBlocking<Unit> {
        val entry = approve(
            amount = 1_000_00L,
            lines = listOf(
                NewLineItem(name = "Weekly shop", total = Money(600_00L), categoryId = groceries.id),
                NewLineItem(name = "Kettle", total = Money(400_00L), categoryId = electronics.id),
            ),
        )
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        val binned = vault.ledger.observeDeleted(LedgerType.DEBIT).first().single()
        assertThat(binned.categoryName).isNull()
        assertThat(binned.displayCategoryName).isEqualTo("Groceries")
        assertThat(binned.displayCategoryColorArgb).isEqualTo(groceries.colorArgb)
        assertThat(binned.additionalCategoryCount).isEqualTo(1)
    }

    /** The bin's plain-entry path, subcategory and all, is undisturbed. */
    @Test
    fun binnedPlainEntry_isUnaffected() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(50_00L),
                occurredAt = 1_700_000_000_000L,
                assignment = com.ledgerflow.core.model.EntryAssignment(
                    categoryId = groceries.id,
                    subcategoryId = dairy.id,
                ),
            ),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        val binned = vault.ledger.observeDeleted(LedgerType.DEBIT).first().single()
        assertThat(binned.displayCategoryName).isEqualTo("Groceries")
        assertThat(binned.subcategoryName).isEqualTo("Dairy")
        assertThat(binned.additionalCategoryCount).isNull()
    }

    /** A plain, non-itemised entry is untouched by any of this. */
    @Test
    fun plainEntry_isUnaffected() = runBlocking<Unit> {
        vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(50_00L),
                occurredAt = 1_700_000_000_000L,
                assignment = com.ledgerflow.core.model.EntryAssignment(categoryId = groceries.id),
            ),
        ).success()

        val row = singleRow()
        assertThat(row.categoryName).isEqualTo("Groceries")
        assertThat(row.displayCategoryName).isEqualTo("Groceries")
        assertThat(row.additionalCategoryCount).isNull()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun category(
        ledger: LedgerType,
        name: String,
        parentId: String? = null,
    ): Category = vault.categories
        .create(NewCategory(ledger, name, parentId = parentId))
        .let {
            assertThat(it).isInstanceOf(TaxonomyResult.Success::class.java)
            (it as TaxonomyResult.Success).value
        }

    private suspend fun approve(amount: Long, lines: List<NewLineItem>): LedgerEntry =
        vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(amount),
                occurredAt = 1_700_000_000_000L,
                lineItems = lines,
            ),
        ).success()

    private suspend fun singleRow(): LedgerListItem =
        vault.ledger.observeEntries(LedgerType.DEBIT, since = Int.MIN_VALUE).asSnapshot().single()

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }
}
