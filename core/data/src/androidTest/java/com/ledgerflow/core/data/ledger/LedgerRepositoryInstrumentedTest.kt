package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.ForeignAmount
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ApproveTransactionUseCase`'s enforcement, against a real database.
 *
 * Law 1 makes this the only path a `ledger_entry` row can take, so everything
 * the schema cannot express has to be true here or it is true nowhere.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryInstrumentedTest {

    private val vault = LedgerTestVault("lf_ledger_test")

    private lateinit var groceries: Category
    private lateinit var vegetables: Category
    private lateinit var salary: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        vegetables = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Vegetables", parentId = groceries.id)).success()
        salary = vault.categories
            .create(NewCategory(LedgerType.CREDIT, "Salary")).success()
    }

    @After
    fun tearDown() = vault.close()

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun approve_manualDebit_isCommittedWithManualProvenance() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(amount = 125_00L, assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()

        assertThat(entry.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(entry.amount).isEqualTo(Money(125_00L))
        assertThat(entry.currency).isEqualTo(LedgerTestVault.BASE_CURRENCY)

        // SPEC.md §5.4: manual entry does not route through pending_transaction,
        // so there is nothing for source_ref_id to point at.
        assertThat(entry.origin.source).isEqualTo(EntrySource.MANUAL)
        assertThat(entry.origin.refId).isNull()

        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(1)
    }

    @Test
    fun approve_derivesLocalDateFromOccurredAt() = runBlocking<Unit> {
        val occurredAt = 1_755_540_000_000L
        val entry = vault.ledger.approve(request(occurredAt = occurredAt)).success()

        assertThat(entry.localDate).isEqualTo(LocalDates.of(occurredAt))
    }

    @Test
    fun approve_bothLedgers_keepsThemDisjoint() = runBlocking<Unit> {
        vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()
        vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(1)
        assertThat(rowCount(LedgerType.CREDIT)).isEqualTo(1)
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test
    fun approve_zeroAmount_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(request(amount = 0L)).error()

        assertThat(error).isEqualTo(LedgerError.AmountNotPositive)
        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(0)
    }

    @Test
    fun approve_negativeAmount_isRefused() = runBlocking<Unit> {
        // Direction is LedgerType, never a sign -- a negative credit would net
        // against a debit somewhere, which is what Law 2 forbids.
        assertThat(vault.ledger.approve(request(amount = -1L)).error())
            .isEqualTo(LedgerError.AmountNotPositive)
    }

    /** SPEC.md §6.1.1 — the invariant a SQLite CHECK cannot hold. */
    @Test
    fun approve_subcategoryUnderAnotherCategory_isRefused() = runBlocking<Unit> {
        val other = vault.categories.create(NewCategory(LedgerType.DEBIT, "Transport")).success()

        val error = vault.ledger.approve(
            request(
                assignment = EntryAssignment(
                    categoryId = other.id,
                    subcategoryId = vegetables.id,
                ),
            ),
        ).error()

        assertThat(error)
            .isEqualTo(LedgerError.SubcategoryNotUnderCategory(vegetables.id, other.id))
        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(0)
    }

    @Test
    fun approve_subcategoryWithNoCategory_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(assignment = EntryAssignment(subcategoryId = vegetables.id)),
        ).error()

        assertThat(error).isEqualTo(LedgerError.SubcategoryWithoutCategory)
    }

    @Test
    fun approve_matchingSubcategory_isAccepted() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                assignment = EntryAssignment(
                    categoryId = groceries.id,
                    subcategoryId = vegetables.id,
                ),
            ),
        ).success()

        assertThat(entry.assignment.subcategoryId).isEqualTo(vegetables.id)
    }

    /**
     * Law 2. Both rows are individually valid, so nothing in the schema objects
     * to a debit filed under "Salary" -- this is the only place it is caught.
     */
    @Test
    fun approve_categoryFromTheOtherLedger_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = salary.id)),
        ).error()

        assertThat(error).isEqualTo(LedgerError.CategoryNotInLedger(salary.id, LedgerType.DEBIT))
        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(0)
    }

    @Test
    fun approve_unknownCategory_isRefused() = runBlocking<Unit> {
        assertThat(vault.ledger.approve(request(EntryAssignment(categoryId = "nope"))).error())
            .isEqualTo(LedgerError.UnknownCategory("nope"))
    }

    @Test
    fun approve_softDeletedCategory_isRefused() = runBlocking<Unit> {
        val doomed = vault.categories.create(NewCategory(LedgerType.DEBIT, "Fads")).success()
        vault.categories.delete(doomed.id, reassignTo = groceries.id)

        assertThat(vault.ledger.approve(request(EntryAssignment(categoryId = doomed.id))).error())
            .isEqualTo(LedgerError.UnknownCategory(doomed.id))
    }

    @Test
    fun approve_unknownPaymentMethod_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                assignment = EntryAssignment(
                    categoryId = groceries.id,
                    paymentMethodId = "missing",
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.UnknownPaymentMethod("missing"))
    }

    @Test
    fun approve_knownPaymentMethod_isAccepted() = runBlocking<Unit> {
        val card = vault.paymentMethods
            .create(NewPaymentMethod(PaymentMethodType.UPI, "GPay")).success()

        val entry = vault.ledger.approve(
            request(
                assignment = EntryAssignment(
                    categoryId = groceries.id,
                    paymentMethodId = card.id,
                ),
            ),
        ).success()

        assertThat(entry.assignment.paymentMethodId).isEqualTo(card.id)
    }

    @Test
    fun approve_blankLineItemName_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(60_00L)),
                    NewLineItem(name = "   ", total = Money(40_00L)),
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.LineItemNameBlank(1))
        assertThat(lineItemCount()).isEqualTo(0)
    }

    /**
     * The reason validation runs inside the transaction rather than before it.
     * A refusal must leave the database exactly as it found it, including the
     * line items that would have gone in first.
     */
    @Test
    fun approve_refusal_writesNothingAtAll() = runBlocking<Unit> {
        vault.ledger.approve(
            request(
                assignment = EntryAssignment(categoryId = salary.id),
                lineItems = listOf(NewLineItem(name = "Rice", total = Money(10_00L))),
            ),
        ).error()

        assertThat(rowCount(LedgerType.DEBIT)).isEqualTo(0)
        assertThat(rowCount(LedgerType.CREDIT)).isEqualTo(0)
        assertThat(lineItemCount()).isEqualTo(0)
    }

    // ── Line items and reconciliation ───────────────────────────────────────

    @Test
    fun approve_balancedLineItems_writesNoUnallocatedRow() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(60_00L)),
                    NewLineItem(name = "Dal", total = Money(40_00L)),
                ),
            ),
        ).success()

        assertThat(entry.lineItems).hasSize(2)
        assertThat(entry.lineItems.map { it.kind }).containsExactly(
            LineItemKind.ITEM,
            LineItemKind.ITEM,
        )
        assertThat(entry.lineItems.map { it.position }).containsExactly(0, 1).inOrder()
    }

    /**
     * SPEC.md §5.3's rule, applied to manual entry: the user may save an
     * unbalanced set of lines, and the difference is written down rather than
     * left as a silent drift between the total and the parts.
     */
    @Test
    fun approve_lineItemsBelowTotal_recordTheDifferenceAsUnallocated() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 100_00L,
                lineItems = listOf(NewLineItem(name = "Rice", total = Money(60_00L))),
            ),
        ).success()

        assertThat(entry.lineItems).hasSize(2)
        val delta = entry.lineItems.single { it.kind == LineItemKind.UNALLOCATED }
        assertThat(delta.total).isEqualTo(Money(40_00L))

        // The whole point: the parts now add up to the total.
        assertThat(Money.sum(entry.lineItems.map { it.total })).isEqualTo(entry.amount)
    }

    @Test
    fun approve_lineItemsAboveTotal_recordANegativeUnallocated() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 50_00L,
                lineItems = listOf(NewLineItem(name = "Rice", total = Money(60_00L))),
            ),
        ).success()

        val delta = entry.lineItems.single { it.kind == LineItemKind.UNALLOCATED }
        assertThat(delta.total).isEqualTo(Money(-10_00L))
        assertThat(Money.sum(entry.lineItems.map { it.total })).isEqualTo(entry.amount)
    }

    /** A discount carries its own sign, so summing lines needs no knowledge of `kind`. */
    @Test
    fun approve_signedDiscountLine_balancesWithoutAnUnallocatedRow() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 90_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(100_00L)),
                    NewLineItem(
                        name = "Festive discount",
                        total = Money(-10_00L),
                        kind = LineItemKind.DISCOUNT,
                    ),
                ),
            ),
        ).success()

        assertThat(entry.lineItems).hasSize(2)
        assertThat(entry.lineItems.none { it.kind == LineItemKind.UNALLOCATED }).isTrue()
    }

    @Test
    fun approve_lineItems_areStoredWithTheEntryInOneTransaction() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 30_00L,
                lineItems = listOf(NewLineItem(name = "Café Latté", total = Money(30_00L))),
            ),
        ).success()

        val stored = vault.session.requireDatabase().ledgerEntryDao().allLineItems()
        assertThat(stored).hasSize(1)
        assertThat(stored.single().entryId).isEqualTo(entry.id)
        assertThat(stored.single().normalizedName).isEqualTo("cafe latte")
    }

    // ── Foreign currency (§5.8) ─────────────────────────────────────────────

    @Test
    fun approve_foreignSpend_keepsBaseAmountAuthoritative() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 4_120_00L,
                foreign = ForeignAmount(
                    amountMinor = 49_50L,
                    currency = "USD",
                    fxRateMicro = 83_230_000L,
                ),
            ),
        ).success()

        // amount_minor is ALWAYS base currency; the foreign trio is display only.
        assertThat(entry.amount).isEqualTo(Money(4_120_00L))
        assertThat(entry.currency).isEqualTo(LedgerTestVault.BASE_CURRENCY)
        assertThat(entry.foreign?.currency).isEqualTo("USD")
        assertThat(entry.foreign?.fxRateMicro).isEqualTo(83_230_000L)
    }

    @Test
    fun approve_foreignCurrencyEqualToBase_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                foreign = ForeignAmount(
                    amountMinor = 100L,
                    currency = "inr",
                    fxRateMicro = 1_000_000L,
                ),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.ForeignCurrencyIsBase("inr"))
    }

    @Test
    fun approve_foreignRateOfZero_isRefused() = runBlocking<Unit> {
        val error = vault.ledger.approve(
            request(
                foreign = ForeignAmount(amountMinor = 100L, currency = "USD", fxRateMicro = 0L),
            ),
        ).error()

        assertThat(error).isEqualTo(LedgerError.ForeignRateNotPositive)
    }

    // ── Repeat-expense chips (§5.4) ─────────────────────────────────────────

    @Test
    fun observeRecentCombos_ranksByUseCountThenRecency() = runBlocking<Unit> {
        val transport = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Transport")).success()

        repeat(3) {
            vault.ledger.approve(
                request(
                    occurredAt = 1_000_000L + it,
                    assignment = EntryAssignment(categoryId = groceries.id),
                ),
            ).success()
        }
        vault.ledger.approve(
            request(
                occurredAt = 9_000_000L,
                assignment = EntryAssignment(categoryId = transport.id),
            ),
        ).success()

        val combos = vault.ledger.observeRecentCombos(LedgerType.DEBIT, limit = 8).first()

        assertThat(combos).hasSize(2)
        assertThat(combos.first().categoryId).isEqualTo(groceries.id)
        assertThat(combos.first().uses).isEqualTo(3)
        assertThat(combos.last().categoryId).isEqualTo(transport.id)
    }

    @Test
    fun observeRecentCombos_neverReturnsTheOtherBook() = runBlocking<Unit> {
        vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        assertThat(vault.ledger.observeRecentCombos(LedgerType.DEBIT, limit = 8).first()).isEmpty()
        assertThat(vault.ledger.observeRecentCombos(LedgerType.CREDIT, limit = 8).first())
            .hasSize(1)
    }

    @Test
    fun observeRecentCombos_skipsUncategorisedEntries() = runBlocking<Unit> {
        // A chip that fills in nothing saves no taps.
        vault.ledger.approve(request()).success()

        assertThat(vault.ledger.observeRecentCombos(LedgerType.DEBIT, limit = 8).first()).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun request(
        assignment: EntryAssignment = EntryAssignment(),
        amount: Long = 100_00L,
        ledger: LedgerType = LedgerType.DEBIT,
        occurredAt: Long = 1_700_000_000_000L,
        foreign: ForeignAmount? = null,
        lineItems: List<NewLineItem> = emptyList(),
    ) = ApprovalRequest(
        ledger = ledger,
        amount = Money(amount),
        occurredAt = occurredAt,
        assignment = assignment,
        foreign = foreign,
        lineItems = lineItems,
    )

    private suspend fun rowCount(ledger: LedgerType): Int =
        vault.session.requireDatabase().ledgerEntryDao().countForLedger(ledger)

    private suspend fun lineItemCount(): Int =
        vault.session.requireDatabase().ledgerEntryDao().allLineItems().size

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun LedgerResult<*>.error(): LedgerError {
        assertThat(this).isInstanceOf(LedgerResult.Failure::class.java)
        return (this as LedgerResult.Failure).error
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
