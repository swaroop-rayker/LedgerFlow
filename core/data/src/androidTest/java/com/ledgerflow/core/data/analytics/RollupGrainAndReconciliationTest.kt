package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0006's recompute, and ADR-0018's grain, asserted against hand-computed
 * figures.
 *
 * **Against hand-computed figures specifically.** ADR-0006 makes reconciliation
 * the same routine as the incremental path, which is what stops the two
 * disagreeing about method — but it also means that if the recompute expression
 * is wrong, it is wrong *identically* in both, and no amount of reconciling will
 * ever notice. So the numbers below are worked out on paper and written down,
 * not produced by a second implementation that could share the same mistake.
 *
 * The vault has no merchant and no payment method, which is deliberate: those
 * dimensions land on the `''` sentinel (§6.1.1), and a bucket that fanned out on
 * `NULL` instead would show up here as extra rows rather than as a crash.
 */
@RunWith(AndroidJUnit4::class)
class RollupGrainAndReconciliationTest {

    private val vault = LedgerTestVault("lf_rollup_test")

    private lateinit var groceries: Category
    private lateinit var home: Category
    private lateinit var salary: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        home = vault.categories.create(NewCategory(LedgerType.DEBIT, "Home")).success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
    }

    @After
    fun tearDown() = vault.close()

    // ── Grain ──────────────────────────────────────────────────────────────

    @Test
    fun plainEntry_writesOneBucket_countingOneTransaction() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)

        val rows = debits()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().categoryId).isEqualTo(groceries.id)
        assertThat(rows.single().sumMinor.minor).isEqualTo(45_000L)
        assertThat(rows.single().txnCount).isEqualTo(1)
        // The dimensions this entry does not have are '' and never NULL.
        assertThat(rows.single().merchantId).isEqualTo("")
        assertThat(rows.single().paymentMethodId).isEqualTo("")
        assertThat(rows.single().subcategoryId).isEqualTo("")
    }

    /**
     * **The case §5.6 left open until P3, in one assertion.**
     *
     * A ₹1,000 bill split ₹600 groceries / ₹400 home writes two buckets — and
     * `txn_count` is **1 in both**, because one payment happened, not two. The
     * money splits; the transaction does not.
     */
    @Test
    fun itemisedEntry_splitsMoneyAcrossBuckets_butCountsOneTransactionInEach() =
        runBlocking<Unit> {
            approve(
                amount = 100_000L,
                categoryId = null,
                lines = listOf(
                    NewLineItem(name = "Rice", total = Money(60_000L), categoryId = groceries.id),
                    NewLineItem(name = "Kettle", total = Money(40_000L), categoryId = home.id),
                ),
            )

            val rows = debits().associateBy { it.categoryId }
            assertThat(rows.keys).containsExactly(groceries.id, home.id)
            assertThat(rows.getValue(groceries.id).sumMinor.minor).isEqualTo(60_000L)
            assertThat(rows.getValue(home.id).sumMinor.minor).isEqualTo(40_000L)
            assertThat(rows.getValue(groceries.id).txnCount).isEqualTo(1)
            assertThat(rows.getValue(home.id).txnCount).isEqualTo(1)
        }

    /**
     * Two lines in the *same* category are still one transaction.
     *
     * This is what `COUNT(DISTINCT entry_id)` is for, and it is the assertion
     * that fails if anyone simplifies it to `COUNT(*)` — which would report two
     * transactions and, worse, would look right in every other test here.
     */
    @Test
    fun twoLinesInOneCategory_countAsOneTransaction() = runBlocking<Unit> {
        approve(
            amount = 100_000L,
            categoryId = null,
            lines = listOf(
                NewLineItem(name = "Rice", total = Money(60_000L), categoryId = groceries.id),
                NewLineItem(name = "Dal", total = Money(40_000L), categoryId = groceries.id),
            ),
        )

        val rows = debits()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().sumMinor.minor).isEqualTo(100_000L)
        assertThat(rows.single().txnCount).isEqualTo(1)
    }

    /**
     * A plain entry and an itemised one in the same bucket merge, and the count
     * is the number of entries.
     */
    @Test
    fun aPlainAndAnItemisedEntry_mergeIntoOneBucket() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        approve(
            amount = 100_000L,
            categoryId = null,
            lines = listOf(
                NewLineItem(name = "Rice", total = Money(60_000L), categoryId = groceries.id),
                NewLineItem(name = "Kettle", total = Money(40_000L), categoryId = home.id),
            ),
        )

        val rows = debits().associateBy { it.categoryId }
        assertThat(rows.getValue(groceries.id).sumMinor.minor).isEqualTo(105_000L)
        assertThat(rows.getValue(groceries.id).txnCount).isEqualTo(2)
        assertThat(rows.getValue(home.id).txnCount).isEqualTo(1)
    }

    /**
     * **The rollup total for a day equals the ledger total for that day**, even
     * when the lines do not sum to the entry.
     *
     * An underspecified bill gets an `UNALLOCATED` line for the difference
     * rather than being refused, so the lines always sum to the amount and the
     * rollup cannot drift from the ledger. The unallocated remainder carries no
     * category and therefore lands on the `''` bucket, which is the honest
     * place for spend nobody has filed.
     */
    @Test
    fun rollupTotal_equalsLedgerTotal_evenWhenLinesDoNotSumToTheEntry() = runBlocking<Unit> {
        approve(
            amount = 100_000L,
            categoryId = null,
            lines = listOf(
                NewLineItem(name = "Rice", total = Money(60_000L), categoryId = groceries.id),
            ),
        )

        val rows = debits()
        assertThat(rows.sumOf { it.sumMinor.minor }).isEqualTo(100_000L)
        val unfiled = rows.single { it.categoryId == "" }
        assertThat(unfiled.sumMinor.minor).isEqualTo(40_000L)
    }

    // ── Law 2 ──────────────────────────────────────────────────────────────

    @Test
    fun theTwoBooksNeverShareABucket() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        approve(amount = 500_000L, categoryId = salary.id, ledger = LedgerType.CREDIT)

        assertThat(debits().map { it.sumMinor.minor }).containsExactly(45_000L)
        assertThat(credits().map { it.sumMinor.minor }).containsExactly(500_000L)
        assertThat(debits().map { it.ledger }.toSet()).containsExactly(LedgerType.DEBIT)
        assertThat(credits().map { it.ledger }.toSet()).containsExactly(LedgerType.CREDIT)
    }

    // ── The three doors that move rollups, and the one that does not ───────

    @Test
    fun softDelete_removesTheEntryFromTheRollups() = runBlocking<Unit> {
        val entry = approve(amount = 45_000L, categoryId = groceries.id)
        assertThat(debits()).hasSize(1)

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id).assertSuccess()

        assertThat(debits()).isEmpty()
    }

    /**
     * Restore is the door that silently changes a figure the user already read,
     * which is exactly why it recomputes.
     */
    @Test
    fun restore_putsTheEntryBackIntoThePastTotal() = runBlocking<Unit> {
        val entry = approve(amount = 45_000L, categoryId = groceries.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id).assertSuccess()
        assertThat(debits()).isEmpty()

        vault.ledger.restoreEntry(LedgerType.DEBIT, entry.id).assertSuccess()

        assertThat(debits()).hasSize(1)
        assertThat(debits().single().sumMinor.minor).isEqualTo(45_000L)
    }

    /**
     * **Purge changes no rollup, and that is the point** (ADR-0006).
     *
     * Rollups are built from live rows; purge only destroys rows that are
     * already binned, so every row it removes was already absent. If someone
     * later adds a "safety" recompute to the purge path, this test does not
     * fail — but the reasoning it records is what should stop them.
     */
    @Test
    fun purge_leavesTheRollupsExactlyAsTheyWere() = runBlocking<Unit> {
        val kept = approve(amount = 45_000L, categoryId = groceries.id)
        val binned = approve(amount = 9_900L, categoryId = home.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned.id).assertSuccess()

        val before = debits()
        vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)

        assertThat(debits()).isEqualTo(before)
        assertThat(debits().single().categoryId).isEqualTo(groceries.id)
        assertThat(kept.id).isNotEmpty()
    }

    // ── Reconciliation ─────────────────────────────────────────────────────

    /**
     * **The base tables win, and the ledger is never written.**
     *
     * A bucket is corrupted behind the repository's back, the nightly pass runs,
     * and the figure comes back correct. The last assertion is the one that
     * matters most: `ledger_entry` is byte-identical afterwards. A
     * reconciliation that "fixed" a disagreement by adjusting the ledger would
     * be a fifth writer and a Law 1 violation, and it would be repairing the
     * source from the cache.
     */
    @Test
    fun reconcile_repairsACorruptedBucket_countsIt_andNeverTouchesTheLedger() =
        runBlocking<Unit> {
            approve(amount = 45_000L, categoryId = groceries.id)
            val ledgerBefore = rawLedgerRows()

            corruptEveryBucket()
            assertThat(debits().single().sumMinor.minor).isEqualTo(1L)

            val repaired = vault.rollups.reconcile()

            assertThat(repaired).isEqualTo(1)
            assertThat(debits().single().sumMinor.minor).isEqualTo(45_000L)
            assertThat(rawLedgerRows()).isEqualTo(ledgerBefore)
        }

    /**
     * A stale row for an entry that is no longer there counts as a repair too.
     *
     * Counting only survivors whose figures moved would report zero for the two
     * failure modes most likely to come from a missed recompute — a bucket that
     * should have vanished, and one that was never written.
     */
    @Test
    fun reconcile_countsAnOrphanedBucketAsARepair() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        insertOrphanBucket()

        val repaired = vault.rollups.reconcile()

        assertThat(repaired).isEqualTo(1)
        assertThat(debits()).hasSize(1)
    }

    @Test
    fun reconcile_onACorrectTable_repairsNothing() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        approve(
            amount = 100_000L,
            categoryId = null,
            lines = listOf(
                NewLineItem(name = "Rice", total = Money(60_000L), categoryId = groceries.id),
                NewLineItem(name = "Kettle", total = Money(40_000L), categoryId = home.id),
            ),
        )
        approve(amount = 500_000L, categoryId = salary.id, ledger = LedgerType.CREDIT)

        // The incremental path has been maintaining the table all along, so the
        // nightly pass has nothing to do. A non-zero answer here would mean the
        // in-transaction recompute and the full pass disagree -- which under
        // ADR-0006 they cannot, because they are the same routine.
        assertThat(vault.rollups.reconcile()).isEqualTo(0)
    }

    /**
     * BUG20 — a rollup that has never been built fills on the next launch.
     *
     * `MIGRATION_8_9` creates `daily_rollup` empty and leaves the filling to the
     * nightly pass, which requires the device to be **idle and charging**. That
     * is correct for repairing drift and wrong for a cold cache: until it runs,
     * every analytics figure silently omits every entry approved before the
     * migration. On the owner's phone the pass had never run, and two real
     * credits — ₹6,300 and ₹250, plainly visible in the Ledger — were reported
     * by D1 as "In ₹0.00".
     *
     * Emptying the table and clearing the stamp reproduces exactly that state.
     */
    @Test
    fun backfill_fillsARollupThatHasNeverBeenBuilt() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        approve(amount = 500_000L, categoryId = salary.id, ledger = LedgerType.CREDIT)
        emptyTheRollupAsAColdCache()
        assertThat(debits()).isEmpty()

        val filled = vault.rollups.backfillIfNeverReconciled()

        assertThat(filled).isGreaterThan(0)
        assertThat(debits().sumOf { it.sumMinor.minor }).isEqualTo(45_000L)
        assertThat(credits().sumOf { it.sumMinor.minor }).isEqualTo(500_000L)
    }

    /**
     * ...and it is a no-op forever after, so it can run on every cold start.
     *
     * The whole point of gating on the stamp rather than on emptiness: a table
     * that is legitimately empty because the ledger is empty must not trigger a
     * full recompute on every launch.
     */
    @Test
    fun backfill_doesNothingOnceReconciliationHasRun() = runBlocking<Unit> {
        approve(amount = 45_000L, categoryId = groceries.id)
        vault.rollups.reconcile()

        // Corrupt it afterwards: a *second* backfill must decline to repair,
        // because repairing drift is the nightly pass's job and this one has
        // already done what it exists for.
        corruptEveryBucket()

        assertThat(vault.rollups.backfillIfNeverReconciled()).isEqualTo(0)
        assertThat(debits().single().sumMinor.minor).isEqualTo(1L)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private suspend fun approve(
        amount: Long,
        categoryId: String?,
        ledger: LedgerType = LedgerType.DEBIT,
        lines: List<NewLineItem> = emptyList(),
    ): LedgerEntry {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = ledger,
                amount = Money(amount),
                occurredAt = OCCURRED_AT,
                assignment = EntryAssignment(categoryId = categoryId),
                lineItems = lines,
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value
    }

    private suspend fun debits(): List<DailyRollupEntity> =
        vault.session.requireDatabase().dailyRollupDao().allFor(LedgerType.DEBIT)

    private suspend fun credits(): List<DailyRollupEntity> =
        vault.session.requireDatabase().dailyRollupDao().allFor(LedgerType.CREDIT)

    /**
     * The state `MIGRATION_8_9` leaves behind: a table with no rows and no
     * record of ever having been reconciled.
     *
     * Both halves matter. Emptying alone would still count as "reconciled" as
     * far as the stamp goes, which is the case the second test relies on.
     */
    private fun emptyTheRollupAsAColdCache() {
        val db = vault.session.requireDatabase().openHelper.writableDatabase
        db.execSQL("DELETE FROM daily_rollup")
        db.execSQL(
            "DELETE FROM app_meta WHERE " + KEY_COLUMN + " = '" +
                AppMetaEntity.KEY_ROLLUP_RECONCILED_AT + "'",
        )
    }

    /** Behind the repository's back, the way real drift would arrive. */
    private fun corruptEveryBucket() {
        vault.session.requireDatabase().openHelper.writableDatabase
            .execSQL("UPDATE daily_rollup SET sum_minor = 1")
    }

    private fun insertOrphanBucket() {
        vault.session.requireDatabase().openHelper.writableDatabase.execSQL(
            "INSERT INTO daily_rollup (local_date, ledger, category_id, subcategory_id, " +
                "merchant_id, payment_method_id, sum_minor, txn_count) VALUES " +
                "(1, 'DEBIT', 'ghost', '', '', '', 999, 1)",
        )
    }

    private fun rawLedgerRows(): List<String> {
        val rows = mutableListOf<String>()
        vault.session.requireDatabase().openHelper.writableDatabase
            .query("SELECT id, amount_minor, local_date, deleted_at FROM ledger_entry ORDER BY id")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows += "${cursor.getString(0)}|${cursor.getLong(1)}|" +
                        "${cursor.getInt(2)}|${cursor.isNull(3)}"
                }
            }
        return rows
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private fun LedgerResult<*>.assertSuccess() {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
    }

    private companion object {
        private const val OCCURRED_AT = 1_700_000_000_000L
    }
}

/** The quoted `key` column of `app_meta`. */
private const val KEY_COLUMN = "\u0060key\u0060"
