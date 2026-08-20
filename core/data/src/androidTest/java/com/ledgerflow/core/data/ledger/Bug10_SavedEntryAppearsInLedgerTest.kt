package com.ledgerflow.core.data.ledger

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.LocalDates
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
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **BUG10 — a saved entry never appeared in the Ledger section.**
 *
 * Reported by the owner on 2026-08-19:
 *
 * > Even after saving a successful expense/income entry it does not show up in
 * > the Ledger section.
 *
 * The save path was never at fault. `ApproveTransactionUseCase` committed the
 * row, and `LedgerRepositoryInstrumentedTest` has asserted that since P1. What
 * was missing is the one this file covers: `LedgerRepository` had a write path
 * and no *list read*, so `LedgerScreen` was a hardcoded empty state that
 * promised the user their entries would appear there and had no way to show
 * them.
 *
 * **The second assertion in each test is the one that matters.** "It appears in
 * its own book" passes even if the read has lost its ledger predicate entirely
 * — a query over `ledger_entry` with no `WHERE` returns the entry too. Only
 * "and it is absent from the other book" can tell the difference, which is why
 * every test here asserts both halves (Law 2, ADR-0002).
 *
 * Run against a real SQLCipher vault rather than a fake DAO, because what is
 * under test is the query: the views' predicates, the two `LEFT JOIN`s, and the
 * ordering the day headers are read off. A fake would assert the Kotlin we
 * wrote instead of the SQL it has to satisfy.
 */
@RunWith(AndroidJUnit4::class)
class Bug10_SavedEntryAppearsInLedgerTest {

    private val vault = LedgerTestVault("lf_bug10_test")

    private lateinit var groceries: Category
    private lateinit var salary: Category
    private lateinit var bigBazaar: Merchant

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
        bigBazaar = vault.merchants.createOrGet("Big Bazaar").success()
    }

    @After
    fun tearDown() = vault.close()

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    fun approvedDebit_appearsInTheDebitBook_andIsAbsentFromTheCreditBook() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                ledger = LedgerType.DEBIT,
                amount = 1_240_50L,
                assignment = EntryAssignment(categoryId = groceries.id),
            ),
        ).success()

        assertThat(page(LedgerType.DEBIT).map { it.id }).containsExactly(entry.id)
        assertThat(page(LedgerType.CREDIT)).isEmpty()
    }

    @Test
    fun approvedCredit_appearsInTheCreditBook_andIsAbsentFromTheDebitBook() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                amount = 85_000_00L,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        assertThat(page(LedgerType.CREDIT).map { it.id }).containsExactly(entry.id)
        assertThat(page(LedgerType.DEBIT)).isEmpty()
    }

    /**
     * Both books populated at once, which is the state the tab actually
     * switches between.
     *
     * The single-entry tests above would still pass against a read that
     * returned *everything* whenever the other book happened to be empty. This
     * one cannot: each page has to contain exactly its own entry and exactly
     * not the other's.
     */
    @Test
    fun bothBooksPopulated_eachPageHoldsOnlyItsOwn() = runBlocking<Unit> {
        val expense = vault.ledger.approve(
            request(
                ledger = LedgerType.DEBIT,
                assignment = EntryAssignment(categoryId = groceries.id),
            ),
        ).success()
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        val debits = page(LedgerType.DEBIT)
        val credits = page(LedgerType.CREDIT)

        assertThat(debits.map { it.id }).containsExactly(expense.id)
        assertThat(credits.map { it.id }).containsExactly(income.id)

        // Stated separately from the two assertions above, because it is the
        // property Law 2 is actually about: no id may be reachable from both.
        assertThat(debits.map { it.id }.intersect(credits.map { it.id }.toSet())).isEmpty()
    }

    // ── What the row carries ────────────────────────────────────────────────

    @Test
    fun listRow_carriesTheAmountAndTheNamesTheScreenShows() = runBlocking<Unit> {
        vault.ledger.approve(
            request(
                amount = 1_240_50L,
                assignment = EntryAssignment(
                    categoryId = groceries.id,
                    merchantId = bigBazaar.id,
                ),
                note = "  weekly shop  ",
            ),
        ).success()

        val row = page(LedgerType.DEBIT).single()

        assertThat(row.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(row.amount).isEqualTo(Money(1_240_50L))
        assertThat(row.currency).isEqualTo(LedgerTestVault.BASE_CURRENCY)
        // Resolved by the query's LEFT JOINs, not by a second lookup per row.
        assertThat(row.categoryName).isEqualTo("Groceries")
        assertThat(row.categoryColorArgb).isEqualTo(groceries.colorArgb)
        assertThat(row.merchantName).isEqualTo("Big Bazaar")
        // Trimmed on the way in by `approve`, so the list shows what was stored.
        assertThat(row.note).isEqualTo("weekly shop")
    }

    /**
     * An entry filed under nothing is still a row.
     *
     * `LEFT JOIN`, not `INNER JOIN`. §5.1 writes a `PENDING` row with
     * `confidence = 0` and no assignment for an unparseable bank SMS, and an
     * inner join would make exactly those invisible in the list while they
     * still counted in every total — a ledger that disagrees with itself.
     */
    @Test
    fun unfiledEntry_isStillListed() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request(assignment = EntryAssignment())).success()

        val row = page(LedgerType.DEBIT).single()

        assertThat(row.id).isEqualTo(entry.id)
        assertThat(row.categoryName).isNull()
        assertThat(row.merchantName).isNull()
    }

    /**
     * A hidden merchant keeps labelling the entries it was already on (§5.5).
     *
     * Soft-delete exists so past entries stay readable; a join that filtered
     * `deleted_at` would turn "hide this merchant" into "erase it from my
     * history", which is not what the confirmation dialog says it does.
     */
    @Test
    fun softDeletedMerchant_stillNamesItsPastEntries() = runBlocking<Unit> {
        vault.ledger.approve(
            request(assignment = EntryAssignment(merchantId = bigBazaar.id)),
        ).success()

        vault.merchants.delete(bigBazaar.id).success()

        assertThat(page(LedgerType.DEBIT).single().merchantName).isEqualTo("Big Bazaar")
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /**
     * Newest first, and by day before time-of-day.
     *
     * The screen's sticky day headers are read straight off this ordering by
     * comparing each row with the one above it. If the order were not
     * `local_date DESC, occurred_at DESC` the headers would repeat a day part
     * way down the list, which looks like duplicate data rather than like a
     * sort bug.
     */
    @Test
    fun entries_areOrderedNewestFirst() = runBlocking<Unit> {
        val oldest = vault.ledger.approve(request(occurredAt = DAY_ONE)).success()
        val newest = vault.ledger.approve(request(occurredAt = DAY_THREE)).success()
        val middle = vault.ledger.approve(request(occurredAt = DAY_TWO)).success()

        assertThat(page(LedgerType.DEBIT).map { it.id })
            .containsExactly(newest.id, middle.id, oldest.id)
            .inOrder()
    }

    // ── The list's window ───────────────────────────────────────────────────

    /**
     * `since` bounds the view and deletes nothing.
     *
     * The two assertions have to be made together. That the windowed page
     * excludes the old entry is half the claim; that the *unwindowed* page
     * still returns it is the half that says the row is still there — and it is
     * the one that would fail if this were ever "simplified" into a purge.
     */
    @Test
    fun since_boundsTheViewWithoutRemovingAnything() = runBlocking<Unit> {
        val old = vault.ledger.approve(request(occurredAt = DAY_ONE)).success()
        val recent = vault.ledger.approve(request(occurredAt = DAY_THREE)).success()

        val windowed = page(LedgerType.DEBIT, since = LocalDates.of(DAY_THREE))

        assertThat(windowed.map { it.id }).containsExactly(recent.id)
        assertThat(page(LedgerType.DEBIT).map { it.id }).containsExactly(recent.id, old.id)
    }

    /** The boundary day is inside the window -- `>=`, not `>`. */
    @Test
    fun since_isInclusiveOfItsOwnDay() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request(occurredAt = DAY_TWO)).success()

        assertThat(page(LedgerType.DEBIT, since = LocalDates.of(DAY_TWO)).map { it.id })
            .containsExactly(entry.id)
    }

    /**
     * A book can be non-empty and its window empty at the same time.
     *
     * This is the state the second empty-state message exists for, and the
     * signal the screen reads to tell the two apart.
     */
    @Test
    fun hasEntries_isTrueEvenWhenEverythingPredatesTheWindow() = runBlocking<Unit> {
        vault.ledger.approve(request(occurredAt = DAY_ONE)).success()

        assertThat(page(LedgerType.DEBIT, since = LocalDates.of(DAY_THREE))).isEmpty()
        assertThat(vault.ledger.observeHasEntries(LedgerType.DEBIT).first()).isTrue()
    }

    @Test
    fun hasEntries_isPerBook() = runBlocking<Unit> {
        vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()

        assertThat(vault.ledger.observeHasEntries(LedgerType.DEBIT).first()).isTrue()
        assertThat(vault.ledger.observeHasEntries(LedgerType.CREDIT).first()).isFalse()
    }

    // ── Deleting (CHANGE#2) ────────────────────────────────────────

    @Test
    fun softDeletedEntry_leavesTheList() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()

        assertThat(vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id))
            .isInstanceOf(LedgerResult.Success::class.java)

        assertThat(page(LedgerType.DEBIT)).isEmpty()
    }

    /**
     * Soft, not gone.
     *
     * The row survives with its line items; only `deleted_at` changes, and the
     * views filter on it. A real `DELETE` would cascade `line_item` away, and
     * those items are the only record of how a bill broke down -- plus the
     * `.lfbk` round-trip compares tables for row-level equality (§13.1, BUG4).
     */
    @Test
    fun softDelete_keepsTheRowAndItsLineItems() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 300_00L,
                lineItems = listOf(
                    NewLineItem(name = "Rice", total = Money(200_00L)),
                    NewLineItem(name = "Dal", total = Money(100_00L)),
                ),
            ),
        ).success()

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        val dao = vault.session.requireDatabase().ledgerEntryDao()
        val row = dao.allForLedger(LedgerType.DEBIT).single { it.id == entry.id }
        assertThat(row.deletedAt).isNotNull()
        assertThat(dao.allLineItems().filter { it.entryId == entry.id }).hasSize(2)
    }

    /**
     * **The Law 2 assertion.** Deleting through the wrong book must do nothing.
     *
     * An id is a UUIDv7 with no ledger encoded in it, so without the `ledger`
     * predicate in the statement a screen showing Expenses could remove a
     * credit row by passing an id it should never have held. The refusal is the
     * *observable* half; the second assertion -- that the entry is still in its
     * own book afterwards -- is the one that would catch a statement which
     * deleted the row and then reported failure for some other reason.
     */
    @Test
    fun softDelete_throughTheWrongBook_refusesAndChangesNothing() = runBlocking<Unit> {
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        val outcome = vault.ledger.softDeleteEntry(LedgerType.DEBIT, income.id)

        assertThat(outcome).isInstanceOf(LedgerResult.Failure::class.java)
        assertThat((outcome as LedgerResult.Failure).error)
            .isEqualTo(LedgerError.EntryNotFound(income.id))
        assertThat(page(LedgerType.CREDIT).map { it.id }).containsExactly(income.id)
    }

    @Test
    fun softDelete_doesNotTouchTheOtherBook() = runBlocking<Unit> {
        val expense = vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, expense.id)

        assertThat(page(LedgerType.DEBIT)).isEmpty()
        assertThat(page(LedgerType.CREDIT).map { it.id }).containsExactly(income.id)
    }

    /** A second confirmation on a stale row reports it rather than re-stamping it. */
    @Test
    fun softDelete_twice_refusesTheSecondTime() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()

        assertThat(vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id))
            .isInstanceOf(LedgerResult.Success::class.java)

        val second = vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)
        assertThat(second).isInstanceOf(LedgerResult.Failure::class.java)
        assertThat((second as LedgerResult.Failure).error)
            .isEqualTo(LedgerError.EntryNotFound(entry.id))
    }

    @Test
    fun softDelete_unknownId_refuses() = runBlocking<Unit> {
        val outcome = vault.ledger.softDeleteEntry(LedgerType.DEBIT, "no-such-entry")

        assertThat(outcome).isInstanceOf(LedgerResult.Failure::class.java)
    }

    /** A deleted entry stops counting towards `hasEntries`, which picks the copy. */
    @Test
    fun softDelete_ofTheLastEntry_leavesTheBookEmpty() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()
        assertThat(vault.ledger.observeHasEntries(LedgerType.DEBIT).first()).isTrue()

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        assertThat(vault.ledger.observeHasEntries(LedgerType.DEBIT).first()).isFalse()
    }

    @Test
    fun emptyBook_pagesToNothingRatherThanFailing() = runBlocking<Unit> {
        assertThat(page(LedgerType.DEBIT)).isEmpty()
        assertThat(page(LedgerType.CREDIT)).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The first page of one book, as a plain list.
     *
     * `asSnapshot` is the only supported way to read items out of a
     * `PagingData` — it is opaque otherwise, by design. Reading it through the
     * public repository API rather than through the DAO is deliberate: BUG10
     * was a missing API, so a test that reached past it to the query would have
     * passed on the broken build.
     */
    private suspend fun page(ledger: LedgerType, since: Int = ALL_TIME) =
        vault.ledger.observeEntries(ledger, since).asSnapshot()

    private fun request(
        ledger: LedgerType = LedgerType.DEBIT,
        amount: Long = 100_00L,
        occurredAt: Long = DAY_ONE,
        assignment: EntryAssignment = EntryAssignment(),
        note: String? = null,
        lineItems: List<NewLineItem> = emptyList(),
    ) = ApprovalRequest(
        ledger = ledger,
        amount = Money(amount),
        occurredAt = occurredAt,
        assignment = assignment,
        note = note,
        lineItems = lineItems,
    )

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private companion object {
        /**
         * No window. These tests are about the ledger predicate, not the list's
         * 30-day view bound, and fixtures dated in 2023 fall outside any window
         * measured from today.
         */
        private const val ALL_TIME = Int.MIN_VALUE

        /** Three distinct days, so the ordering test exercises `local_date`. */
        private const val DAY_ONE = 1_700_000_000_000L
        private const val DAY_TWO = DAY_ONE + 86_400_000L
        private const val DAY_THREE = DAY_ONE + 2 * 86_400_000L
    }
}
