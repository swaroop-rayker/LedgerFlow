package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-line filing — the itemised entry (SPEC.md §5.4, ADR-0018).
 *
 * The scenario throughout is the one the feature exists for: one payment at a
 * shop that sells across categories. A ₹1,000 bill is not ₹1,000 of groceries
 * because it was paid at a grocer — it is ₹600 of groceries and ₹400 of
 * electronics, and those are the only figures analytics will ever have, because
 * an itemised entry files nothing at the entry level.
 *
 * That is why these refusals matter more than their entry-level counterparts
 * rather than less. `line_item` carries no foreign key to `category`, so if a
 * bad category reaches a row here, nothing downstream will notice: the spend
 * simply attributes to a category from the other book, or to none at all.
 */
@RunWith(AndroidJUnit4::class)
class LineItemFilingTest {

    private val vault = LedgerTestVault("lf_line_item_filing_test")

    private lateinit var groceries: Category
    private lateinit var dairy: Category
    private lateinit var electronics: Category
    private lateinit var salary: Category
    private lateinit var bonus: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = category(LedgerType.DEBIT, "Groceries")
        dairy = category(LedgerType.DEBIT, "Dairy", parentId = groceries.id)
        electronics = category(LedgerType.DEBIT, "Electronics")
        salary = category(LedgerType.CREDIT, "Salary")
        bonus = category(LedgerType.CREDIT, "Bonus", parentId = salary.id)
    }

    @After
    fun tearDown() = vault.close()

    // ── The scenario ────────────────────────────────────────────────────────

    /** The whole feature, in one assertion: one payment, two categories. */
    @Test
    fun approve_itemsInDifferentCategories_eachKeepsItsOwn() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 1_000_00L,
                lineItems = listOf(
                    NewLineItem(
                        name = "Weekly shop",
                        total = Money(600_00L),
                        categoryId = groceries.id,
                    ),
                    NewLineItem(
                        name = "Kettle",
                        total = Money(400_00L),
                        categoryId = electronics.id,
                    ),
                ),
            ),
        ).success()

        val byName = entry.lineItems.associateBy { it.name }
        assertThat(byName.getValue("Weekly shop").categoryId).isEqualTo(groceries.id)
        assertThat(byName.getValue("Kettle").categoryId).isEqualTo(electronics.id)

        // ADR-0018: the entry itself files nothing. There is no single category
        // that would be true of this row, and inventing one is what the whole
        // design avoids.
        assertThat(entry.assignment.categoryId).isNull()
        assertThat(Money.sum(entry.lineItems.map { it.total })).isEqualTo(entry.amount)
    }

    @Test
    fun approve_itemWithSubcategory_storesBothLevels() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 240_00L,
                lineItems = listOf(
                    NewLineItem(
                        name = "Milk 1L",
                        total = Money(240_00L),
                        categoryId = groceries.id,
                        subcategoryId = dairy.id,
                    ),
                ),
            ),
        ).success()

        val line = entry.lineItems.single()
        assertThat(line.categoryId).isEqualTo(groceries.id)
        assertThat(line.subcategoryId).isEqualTo(dairy.id)
    }

    /**
     * Quantity and unit price survive the round trip, and the line total is
     * their product computed the one way Law 3 permits.
     *
     * The multiplication happens above this layer -- the repository stores what
     * it is handed -- so what is pinned here is that the three values stay
     * consistent through the write, which is what a later reader of the row
     * needs in order to explain where the number came from.
     */
    @Test
    fun approve_itemWithQuantity_storesPriceQuantityAndTheirProduct() = runBlocking<Unit> {
        val unitPrice = Money(120_00L)
        val quantity = Quantity.ofUnits(2)

        val entry = vault.ledger.approve(
            request(
                amount = 240_00L,
                lineItems = listOf(
                    NewLineItem(
                        name = "Milk 1L",
                        total = unitPrice * quantity,
                        quantityMilli = quantity.milli,
                        unitPrice = unitPrice,
                        categoryId = groceries.id,
                    ),
                ),
            ),
        ).success()

        val line = entry.lineItems.single()
        assertThat(line.unitPrice).isEqualTo(unitPrice)
        assertThat(line.quantityMilli).isEqualTo(quantity.milli)
        assertThat(line.total).isEqualTo(Money(240_00L))
    }

    /**
     * A part-itemised bill: the user broke out what they cared about and left
     * the rest. §5.4 allows saving it, and the remainder becomes a row rather
     * than a discrepancy.
     */
    @Test
    fun approve_partiallyItemised_leavesTheRemainderUnallocated() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 1_000_00L,
                lineItems = listOf(
                    NewLineItem(name = "Kettle", total = Money(400_00L), categoryId = electronics.id),
                ),
            ),
        ).success()

        val remainder = entry.lineItems.single { it.kind == LineItemKind.UNALLOCATED }
        assertThat(remainder.total).isEqualTo(Money(600_00L))
        assertThat(remainder.categoryId).isNull()
        assertThat(Money.sum(entry.lineItems.map { it.total })).isEqualTo(entry.amount)
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    /**
     * Law 2, one level down.
     *
     * Both rows are individually valid — a real debit entry, a real category —
     * and nothing in the schema relates them, so this check is the only thing
     * between "Salary" and a grocery bill.
     */
    @Test
    fun approve_itemCategoryFromTheOtherBook_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(60_00L), categoryId = groceries.id),
                    NewLineItem(name = "Payday", total = Money(40_00L), categoryId = salary.id),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(
            LedgerError.LineItemCategoryNotInLedger(1, salary.id, LedgerType.DEBIT),
        )
    }

    /** The mirror, so the check is not accidentally one-directional. */
    @Test
    fun approve_creditItemFiledUnderADebitCategory_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Refund", total = Money(100_00L), categoryId = groceries.id),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(
            LedgerError.LineItemCategoryNotInLedger(0, groceries.id, LedgerType.CREDIT),
        )
    }

    @Test
    fun approve_itemCategoryThatDoesNotExist_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(100_00L), categoryId = "not-an-id"),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.LineItemUnknownCategory(0, "not-an-id"))
    }

    /**
     * A hidden category reads as absent, exactly as it does at the entry level.
     *
     * The user can hide a category with entries already filed under it
     * (ADR-0016) — what must not happen is a *new* line landing there, which
     * would be spend filed somewhere the user has said they no longer use.
     */
    @Test
    fun approve_itemCategoryThatWasHidden_isRefused() = runBlocking<Unit> {
        vault.categories.delete(electronics.id, reassignTo = null).successUnit()

        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Kettle", total = Money(100_00L), categoryId = electronics.id),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.LineItemUnknownCategory(0, electronics.id))
    }

    /** §6.1.1's parent invariant. `Dairy` is under `Groceries`, not `Electronics`. */
    @Test
    fun approve_itemSubcategoryUnderAnotherParent_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(
                        name = "Kettle",
                        total = Money(100_00L),
                        categoryId = electronics.id,
                        subcategoryId = dairy.id,
                    ),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(
            LedgerError.LineItemSubcategoryNotUnderCategory(0, dairy.id, electronics.id),
        )
    }

    /** Reachable by clearing a line's category after choosing its subcategory. */
    @Test
    fun approve_itemSubcategoryWithoutACategory_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Milk", total = Money(100_00L), subcategoryId = dairy.id),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.LineItemSubcategoryWithoutCategory(0))
    }

    /**
     * The refusal is reported by *position*, so a form with a dozen lines can
     * point at the one that is wrong. An error naming only the category id
     * sends the user hunting through the list for it.
     */
    @Test
    fun approve_refusal_namesTheOffendingLine() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "A", total = Money(25_00L), categoryId = groceries.id),
                    NewLineItem(name = "B", total = Money(25_00L), categoryId = electronics.id),
                    NewLineItem(name = "C", total = Money(50_00L), categoryId = bonus.id),
                ),
            ),
        ).error()

        assertThat(error).isInstanceOf(LedgerError.LineItemCategoryNotInLedger::class.java)
        assertThat((error as LedgerError.LineItemCategoryNotInLedger).position).isEqualTo(2)
    }

    /**
     * A refused approval writes nothing at all — not the entry, not the lines
     * that were fine.
     *
     * The rules run inside the approval's transaction precisely so that this
     * holds; a check that ran before it opened could pass and then have the
     * insert half-apply.
     */
    @Test
    fun approve_refusedFiling_leavesNoRowsBehind() = runBlocking<Unit> {
        vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(50_00L), categoryId = groceries.id),
                    NewLineItem(name = "Payday", total = Money(50_00L), categoryId = salary.id),
                ),
            ),
        ).error()

        val database = vault.session.requireDatabase()
        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.DEBIT)).isEqualTo(0)
        assertThat(database.ledgerEntryDao().allLineItems()).isEmpty()
    }

    /** Uncategorised lines stay legal: itemising is not the same as filing. */
    @Test
    fun approve_itemsWithNoCategory_areStillAccepted() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 100_00L,
                assignment = EntryAssignment(categoryId = groceries.id),
                lineItems = listOf(NewLineItem(name = "Rice", total = Money(100_00L))),
            ),
        ).success()

        assertThat(entry.lineItems.single().categoryId).isNull()
        assertThat(entry.assignment.categoryId).isEqualTo(groceries.id)
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

    private fun request(
        assignment: EntryAssignment = EntryAssignment(),
        amount: Long = 100_00L,
        ledger: LedgerType = LedgerType.DEBIT,
        lineItems: List<NewLineItem> = emptyList(),
    ) = ApprovalRequest(
        ledger = ledger,
        amount = Money(amount),
        occurredAt = 1_700_000_000_000L,
        assignment = assignment,
        lineItems = lineItems,
    )

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun LedgerResult<*>.error(): LedgerError {
        assertThat(this).isInstanceOf(LedgerResult.Failure::class.java)
        return (this as LedgerResult.Failure).error
    }

    private fun TaxonomyResult<Unit>.successUnit() {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
    }
}
