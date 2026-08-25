package com.ledgerflow.core.data.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.dao.DeletedEntryRow
import com.ledgerflow.core.database.dao.LedgerListRow
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * `LedgerListRow.toDomain` — mechanical, and the one place a column could be
 * dropped on the way from the query to the screen without anything else
 * noticing (SPEC.md §5.5, ADR-0018).
 *
 * The line-item columns matter more than they look: they exist so an itemised
 * entry's row can show *something* other than "Unfiled", and a mapper that
 * silently drops them would put that regression back with no compiler error to
 * catch it -- `LedgerListItem`'s new fields all have defaults.
 */
class LedgerMappersTest {

    private fun row(
        categoryName: String? = null,
        categoryColorArgb: Int? = null,
        lineItemCategoryName: String? = null,
        lineItemCategoryColorArgb: Int? = null,
        lineItemCategoryCount: Int = 0,
    ) = LedgerListRow(
        id = "entry-1",
        amountMinor = Money(1_000_00L),
        currency = "INR",
        localDate = 20_000,
        occurredAt = 1_700_000_000_000L,
        categoryName = categoryName,
        categoryColorArgb = categoryColorArgb,
        merchantName = "Reliance Fresh",
        note = null,
        lineItemCategoryName = lineItemCategoryName,
        lineItemCategoryColorArgb = lineItemCategoryColorArgb,
        lineItemCategoryCount = lineItemCategoryCount,
    )

    @Test
    fun toDomain_carriesTheLineItemColumnsThrough() {
        val item = row(
            lineItemCategoryName = "Groceries",
            lineItemCategoryColorArgb = 0xFF00FF00.toInt(),
            lineItemCategoryCount = 2,
        ).toDomain(LedgerType.DEBIT)

        assertThat(item.lineItemCategoryName).isEqualTo("Groceries")
        assertThat(item.lineItemCategoryColorArgb).isEqualTo(0xFF00FF00.toInt())
        assertThat(item.lineItemCategoryCount).isEqualTo(2)
    }

    @Test
    fun toDomain_aPlainEntry_stillReadsItsOwnCategory() {
        val item = row(categoryName = "Salary", categoryColorArgb = 0xFF0000FF.toInt())
            .toDomain(LedgerType.CREDIT)

        assertThat(item.displayCategoryName).isEqualTo("Salary")
        assertThat(item.displayCategoryColorArgb).isEqualTo(0xFF0000FF.toInt())
        assertThat(item.additionalCategoryCount).isNull()
    }

    @Test
    fun toDomain_anItemisedEntry_fallsBackToItsLineItemCategory() {
        val item = row(
            categoryName = null,
            lineItemCategoryName = "Groceries",
            lineItemCategoryColorArgb = 0xFF00FF00.toInt(),
            lineItemCategoryCount = 1,
        ).toDomain(LedgerType.DEBIT)

        assertThat(item.displayCategoryName).isEqualTo("Groceries")
        assertThat(item.displayCategoryColorArgb).isEqualTo(0xFF00FF00.toInt())
        // One category and nothing else -- there is no "+" left to say.
        assertThat(item.additionalCategoryCount).isNull()
    }

    @Test
    fun toDomain_anItemisedEntrySpanningCategories_countsTheRest() {
        val item = row(
            categoryName = null,
            lineItemCategoryName = "Groceries",
            lineItemCategoryCount = 3,
        ).toDomain(LedgerType.DEBIT)

        assertThat(item.displayCategoryName).isEqualTo("Groceries")
        assertThat(item.additionalCategoryCount).isEqualTo(2)
    }

    /** Every line item uncategorised: still nothing to show, same as before ADR-0018. */
    @Test
    fun toDomain_anItemisedEntryWithNoCategorisedLines_hasNoDisplayCategory() {
        val item = row(categoryName = null, lineItemCategoryCount = 0).toDomain(LedgerType.DEBIT)

        assertThat(item.displayCategoryName).isNull()
        assertThat(item.displayCategoryColorArgb).isNull()
        assertThat(item.additionalCategoryCount).isNull()
    }

    // ── The bin's row, which has the same fallback (ADR-0018) ───────────────

    private fun binRow(
        categoryName: String? = null,
        categoryColorArgb: Int? = null,
        subcategoryName: String? = null,
        lineItemCategoryName: String? = null,
        lineItemCategoryColorArgb: Int? = null,
        lineItemCategoryCount: Int = 0,
    ) = DeletedEntryRow(
        id = "entry-1",
        ledger = LedgerType.DEBIT,
        amountMinor = Money(1_000_00L),
        currency = "INR",
        occurredAt = 1_700_000_000_000L,
        deletedAt = 1_700_000_100_000L,
        categoryName = categoryName,
        categoryColorArgb = categoryColorArgb,
        subcategoryName = subcategoryName,
        merchantName = "Reliance Fresh",
        note = null,
        lineItemCategoryName = lineItemCategoryName,
        lineItemCategoryColorArgb = lineItemCategoryColorArgb,
        lineItemCategoryCount = lineItemCategoryCount,
    )

    @Test
    fun deletedToDomain_anItemisedEntry_fallsBackToItsLineItemCategory() {
        val entry = binRow(
            lineItemCategoryName = "Groceries",
            lineItemCategoryColorArgb = 0xFF00FF00.toInt(),
            lineItemCategoryCount = 3,
        ).toDomain()

        assertThat(entry.displayCategoryName).isEqualTo("Groceries")
        assertThat(entry.displayCategoryColorArgb).isEqualTo(0xFF00FF00.toInt())
        assertThat(entry.additionalCategoryCount).isEqualTo(2)
    }

    /**
     * A plain entry keeps its subcategory, which the bin shows and the list
     * does not. Nothing about the fallback disturbs it.
     */
    @Test
    fun deletedToDomain_aPlainEntry_keepsItsOwnCategoryAndSubcategory() {
        val entry = binRow(
            categoryName = "Groceries",
            categoryColorArgb = 0xFF0000FF.toInt(),
            subcategoryName = "Dairy",
        ).toDomain()

        assertThat(entry.displayCategoryName).isEqualTo("Groceries")
        assertThat(entry.subcategoryName).isEqualTo("Dairy")
        assertThat(entry.additionalCategoryCount).isNull()
    }

    @Test
    fun deletedToDomain_withNoCategorisedLines_hasNoDisplayCategory() {
        val entry = binRow(lineItemCategoryCount = 0).toDomain()

        assertThat(entry.displayCategoryName).isNull()
        assertThat(entry.additionalCategoryCount).isNull()
    }
}
