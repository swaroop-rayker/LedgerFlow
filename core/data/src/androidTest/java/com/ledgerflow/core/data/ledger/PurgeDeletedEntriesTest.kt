package com.ledgerflow.core.data.ledger

import androidx.paging.testing.asSnapshot
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
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Erase deleted entries" — the only operation in LedgerFlow that destroys
 * committed ledger data (SPEC.md §5.5).
 *
 * Everything else in the app is recoverable: a soft delete clears a column, a
 * restore replays a `.lfbk`. This is not, which is why it is tested against a
 * real SQLCipher vault rather than a fake, and why the assertion that matters
 * most is not "the deleted rows went" but **"the ones I kept are still here"**.
 *
 * The `VACUUM` afterwards gets its own coverage for a specific reason: it
 * rewrites the entire encrypted database file, and a rewrite that went wrong
 * would not present as a failed test in production — it would present as a user
 * staring at the Recovery screen with an unreadable vault.
 */
@RunWith(AndroidJUnit4::class)
class PurgeDeletedEntriesTest {

    private val vault = LedgerTestVault("lf_purge_test")

    private lateinit var groceries: Category
    private lateinit var vegetables: Category
    private lateinit var salary: Category
    private lateinit var bigBazaar: Merchant

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        vegetables = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Vegetables", parentId = groceries.id))
            .success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
        bigBazaar = vault.merchants.createOrGet("Big Bazaar").success()
    }

    @After
    fun tearDown() = vault.close()

    // ── What goes, and what stays ───────────────────────────────────────────

    @Test
    fun purge_removesSoftDeletedEntries_andKeepsTheLiveOnes() = runBlocking<Unit> {
        val kept = vault.ledger.approve(request()).success()
        val doomed = vault.ledger.approve(request(amount = 250_00L)).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, doomed.id)

        assertThat(purge()).isEqualTo(1)

        val remaining = allRows(LedgerType.DEBIT)
        assertThat(remaining.map { it.id }).containsExactly(kept.id)
    }

    /**
     * The row is gone from the *table*, not merely hidden.
     *
     * A soft delete already makes it invisible to every query the app runs, so
     * reading through the views would pass whether or not the purge did
     * anything at all. This reads the base table directly, which is the only
     * way to tell the two apart.
     */
    @Test
    fun purge_removesTheRowFromTheTableNotJustTheViews() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        assertThat(allRows(LedgerType.DEBIT).map { it.id }).containsExactly(entry.id)

        purge()

        assertThat(allRows(LedgerType.DEBIT)).isEmpty()
    }

    /**
     * Line items go with their entry, by foreign key.
     *
     * `PRAGMA foreign_keys = ON` is set in `LedgerFlowDatabaseFactory`, so the
     * `ON DELETE CASCADE` on `line_item.entry_id` is live rather than
     * decorative. If that pragma were ever dropped this test is what would
     * notice: the entry would go and its items would be left behind pointing at
     * nothing.
     */
    @Test
    fun purge_cascadesToLineItems() = runBlocking<Unit> {
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

        assertThat(lineItemsOf(entry.id)).hasSize(2)

        purge()

        assertThat(lineItemsOf(entry.id)).isEmpty()
    }

    @Test
    fun purge_sweepsBothBooks() = runBlocking<Unit> {
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
        vault.ledger.softDeleteEntry(LedgerType.CREDIT, income.id)

        assertThat(purge()).isEqualTo(2)

        assertThat(allRows(LedgerType.DEBIT)).isEmpty()
        assertThat(allRows(LedgerType.CREDIT)).isEmpty()
    }

    /** One book's purge never reaches into the other (Law 2). */
    @Test
    fun purge_ofOneBook_leavesTheOthersDeletedRowsAlone() = runBlocking<Unit> {
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.CREDIT, income.id)

        assertThat(vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)).isEqualTo(0)

        assertThat(allRows(LedgerType.CREDIT).map { it.id }).containsExactly(income.id)
    }

    @Test
    fun purge_withNothingDeleted_removesNothing() = runBlocking<Unit> {
        val kept = vault.ledger.approve(request()).success()

        assertThat(purge()).isEqualTo(0)

        assertThat(allRows(LedgerType.DEBIT).map { it.id }).containsExactly(kept.id)
    }

    // ── The count the confirmation is built from ────────────────────────────

    @Test
    fun deletedCount_tracksWhatThePurgeWouldTake() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()
        assertThat(deletedCount(LedgerType.DEBIT)).isEqualTo(0)

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)
        assertThat(deletedCount(LedgerType.DEBIT)).isEqualTo(1)

        purge()
        assertThat(deletedCount(LedgerType.DEBIT)).isEqualTo(0)
    }

    @Test
    fun deletedCount_isPerBook() = runBlocking<Unit> {
        val expense = vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, expense.id)

        assertThat(deletedCount(LedgerType.DEBIT)).isEqualTo(1)
        assertThat(deletedCount(LedgerType.CREDIT)).isEqualTo(0)
    }

    // ── The compaction ──────────────────────────────────────────────────────

    /**
     * **The vault survives `VACUUM`.**
     *
     * The most important test in this file. `VACUUM` rewrites the whole
     * SQLCipher database, and a rewrite that lost the page settings or the key
     * would not fail loudly here — it would fail on the user's next launch, as
     * an undecryptable vault and a Recovery screen. So this compacts a database
     * that still has real content and then reads that content back.
     */
    @Test
    fun compactStorage_leavesTheVaultReadable() = runBlocking<Unit> {
        val kept = vault.ledger.approve(
            request(
                amount = 1_240_50L,
                assignment = EntryAssignment(categoryId = groceries.id),
                lineItems = listOf(NewLineItem(name = "Rice", total = Money(1_240_50L))),
            ),
        ).success()
        val doomed = vault.ledger.approve(request()).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, doomed.id)

        purge()

        val rows = allRows(LedgerType.DEBIT)
        assertThat(rows.map { it.id }).containsExactly(kept.id)
        assertThat(rows.single().amountMinor).isEqualTo(Money(1_240_50L))
        assertThat(lineItemsOf(kept.id)).hasSize(1)
        // The taxonomy has to come back too -- VACUUM rewrites every table, not
        // just the one the purge touched.
        assertThat(vault.categories.find(groceries.id)?.name).isEqualTo("Groceries")
    }

    /** Compaction on its own is safe, and safe to repeat. */
    @Test
    fun compactStorage_isSafeWithNothingToReclaim() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()

        vault.storage.compactStorage()
        vault.storage.compactStorage()

        assertThat(allRows(LedgerType.DEBIT).map { it.id }).containsExactly(entry.id)
    }

    // ── The bin's reads and per-row writes (ADR-0015) ───────────────────

    /**
     * The bin lists what the views cannot.
     *
     * `debit_entries` and `credit_entries` filter `deleted_at IS NULL`, so a
     * binned row is invisible to every other read in the app. This is the query
     * that has to see it, and it reads the base table with `:ledger` bound.
     */
    @Test
    fun observeDeleted_returnsOnlyBinnedEntries() = runBlocking<Unit> {
        val kept = vault.ledger.approve(request()).success()
        val binned = vault.ledger.approve(request(amount = 250_00L)).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned.id)

        val rows = vault.ledger.observeDeleted(LedgerType.DEBIT).first()

        assertThat(rows.map { it.id }).containsExactly(binned.id)
        assertThat(rows.map { it.id }).doesNotContain(kept.id)
    }

    /**
     * The bin resolves the subcategory, which the Ledger's own rows do not.
     *
     * It earns its place there: the bin is where a user tells two otherwise
     * identical entries apart before deciding which to keep.
     */
    @Test
    fun observeDeleted_resolvesMerchantCategoryAndSubcategory() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(
                amount = 1_240_50L,
                assignment = EntryAssignment(
                    categoryId = groceries.id,
                    subcategoryId = vegetables.id,
                    merchantId = bigBazaar.id,
                ),
            ),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)

        val row = vault.ledger.observeDeleted(LedgerType.DEBIT).first().single()

        assertThat(row.categoryName).isEqualTo("Groceries")
        assertThat(row.subcategoryName).isEqualTo("Vegetables")
        assertThat(row.merchantName).isEqualTo("Big Bazaar")
        assertThat(row.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(row.amount).isEqualTo(Money(1_240_50L))
    }

    /** Each book's bin holds only its own rows (Law 2). */
    @Test
    fun observeDeleted_isPerBook() = runBlocking<Unit> {
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
        vault.ledger.softDeleteEntry(LedgerType.CREDIT, income.id)

        assertThat(vault.ledger.observeDeleted(LedgerType.DEBIT).first().map { it.id })
            .containsExactly(expense.id)
        assertThat(vault.ledger.observeDeleted(LedgerType.CREDIT).first().map { it.id })
            .containsExactly(income.id)
    }

    /**
     * **Restore puts the entry back where every read can see it again.**
     *
     * This is the payoff of `softDeleteEntry` keeping the row: clearing
     * `deleted_at` returns it to the views, and therefore to the list, the
     * totals and the exports, in one statement.
     */
    @Test
    fun restore_returnsTheEntryToItsBook() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            request(assignment = EntryAssignment(categoryId = groceries.id)),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)
        assertThat(page(LedgerType.DEBIT)).isEmpty()

        assertThat(vault.ledger.restoreEntry(LedgerType.DEBIT, entry.id))
            .isInstanceOf(LedgerResult.Success::class.java)

        assertThat(page(LedgerType.DEBIT).map { it.id }).containsExactly(entry.id)
        assertThat(vault.ledger.observeDeleted(LedgerType.DEBIT).first()).isEmpty()
    }

    /**
     * **The Law 2 assertion for restore.** Through the wrong book it does
     * nothing, and says so.
     */
    @Test
    fun restore_throughTheWrongBook_refusesAndChangesNothing() = runBlocking<Unit> {
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.CREDIT, income.id)

        val outcome = vault.ledger.restoreEntry(LedgerType.DEBIT, income.id)

        assertThat(outcome).isInstanceOf(LedgerResult.Failure::class.java)
        assertThat((outcome as LedgerResult.Failure).error)
            .isEqualTo(LedgerError.EntryNotFound(income.id))
        // Still binned, in its own book, untouched.
        assertThat(vault.ledger.observeDeleted(LedgerType.CREDIT).first().map { it.id })
            .containsExactly(income.id)
    }

    /** Restoring something that was never binned affects nothing. */
    @Test
    fun restore_ofALiveEntry_refuses() = runBlocking<Unit> {
        val entry = vault.ledger.approve(request()).success()

        assertThat(vault.ledger.restoreEntry(LedgerType.DEBIT, entry.id))
            .isInstanceOf(LedgerResult.Failure::class.java)
        assertThat(page(LedgerType.DEBIT).map { it.id }).containsExactly(entry.id)
    }

    /**
     * Erasing one chosen row leaves the rest of the bin alone.
     *
     * The whole difference between a bin you pick from and one you can only
     * empty.
     */
    @Test
    fun purgeDeletedEntry_destroysOnlyThatRow() = runBlocking<Unit> {
        val doomed = vault.ledger.approve(request()).success()
        val spared = vault.ledger.approve(request(amount = 250_00L)).success()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, doomed.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, spared.id)

        assertThat(vault.ledger.purgeDeletedEntry(LedgerType.DEBIT, doomed.id)).isEqualTo(1)

        assertThat(allRows(LedgerType.DEBIT).map { it.id }).containsExactly(spared.id)
    }

    /**
     * **A live entry can never be reached through the per-row purge.**
     *
     * `AND deleted_at IS NOT NULL` is what makes that true. Without it this
     * statement would destroy any entry by id, which is a thing no screen
     * should be able to ask for -- and the bin only ever shows binned rows, so
     * nothing would have caught it.
     */
    @Test
    fun purgeDeletedEntry_refusesALiveEntry() = runBlocking<Unit> {
        val live = vault.ledger.approve(request()).success()

        assertThat(vault.ledger.purgeDeletedEntry(LedgerType.DEBIT, live.id)).isEqualTo(0)

        assertThat(page(LedgerType.DEBIT).map { it.id }).containsExactly(live.id)
    }

    @Test
    fun purgeDeletedEntry_throughTheWrongBook_destroysNothing() = runBlocking<Unit> {
        val income = vault.ledger.approve(
            request(
                ledger = LedgerType.CREDIT,
                assignment = EntryAssignment(categoryId = salary.id),
            ),
        ).success()
        vault.ledger.softDeleteEntry(LedgerType.CREDIT, income.id)

        assertThat(vault.ledger.purgeDeletedEntry(LedgerType.DEBIT, income.id)).isEqualTo(0)

        assertThat(allRows(LedgerType.CREDIT).map { it.id }).containsExactly(income.id)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Both books, the way `PurgeDeletedEntriesUseCase` does it. */
    private suspend fun purge(): Int {
        val count = LedgerType.entries.sumOf { vault.ledger.purgeDeletedEntries(it) }
        if (count > 0) vault.storage.compactStorage()
        return count
    }

    private suspend fun allRows(ledger: LedgerType) =
        vault.session.requireDatabase().ledgerEntryDao().allForLedger(ledger)

    /** What the Ledger's own list would show -- i.e. what is *not* binned. */
    private suspend fun page(ledger: LedgerType) =
        vault.ledger.observeEntries(ledger, since = Int.MIN_VALUE).asSnapshot()

    private suspend fun lineItemsOf(entryId: String) =
        vault.session.requireDatabase().ledgerEntryDao().allLineItems()
            .filter { it.entryId == entryId }

    private suspend fun deletedCount(ledger: LedgerType) =
        vault.ledger.observeDeletedCount(ledger).first()

    private fun request(
        ledger: LedgerType = LedgerType.DEBIT,
        amount: Long = 100_00L,
        assignment: EntryAssignment = EntryAssignment(),
        lineItems: List<NewLineItem> = emptyList(),
    ) = ApprovalRequest(
        ledger = ledger,
        amount = Money(amount),
        occurredAt = OCCURRED_AT,
        assignment = assignment,
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
        private const val OCCURRED_AT = 1_700_000_000_000L
    }
}
