package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A1–A5's reads, against a seeded vault (`SPEC.md` §5.6).
 *
 * The figures are worked out by hand and written down, for the reason
 * `RollupGrainAndReconciliationTest` gives about the recompute: a test that
 * derives its expectation the same way the code does will agree with the code
 * whether or not either is right.
 *
 * The seed is deliberately awkward — a plain entry, an itemised one split across
 * two categories, a credit on the same day, and a binned row — so the two
 * properties most easily got wrong have something to fail on: that `txn_count`
 * does not double-count a split bill, and that the two books never mix.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsSnapshotTest {

    private val vault = LedgerTestVault("lf_analytics_test")
    private lateinit var analytics: DefaultAnalyticsRepository

    private lateinit var groceries: Category
    private lateinit var home: Category
    private lateinit var salary: Category

    /** A fixed day, so a run at midnight cannot straddle a boundary. */
    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()
    private val occurredAt: Long =
        LocalDate.of(2026, 6, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        analytics = DefaultAnalyticsRepository(vault.session, kotlinx.coroutines.Dispatchers.IO)
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        home = vault.categories.create(NewCategory(LedgerType.DEBIT, "Home")).success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
    }

    @After
    fun tearDown() = vault.close()

    private suspend fun seed() {
        // ₹450 groceries, plain.
        approve(45_000L, groceries.id)
        // ₹1,000 bill split ₹600 groceries / ₹400 home. ONE transaction.
        approve(
            100_000L,
            null,
            lines = listOf(
                NewLineItem("Rice", Money(60_000L), categoryId = groceries.id),
                NewLineItem("Kettle", Money(40_000L), categoryId = home.id),
            ),
        )
        // The other book, same day.
        approve(500_000L, salary.id, ledger = LedgerType.CREDIT)
        // Binned: present in the table, absent from every figure below.
        val binned = approve(9_900L, home.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned)
    }

    @Test
    fun theWindowTotal_countsEveryLiveDebitAndNoCredit() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        // 45_000 + 100_000. The credit is a different book (Law 2) and the
        // binned row is not live.
        assertThat(snapshot.total.minor).isEqualTo(145_000L)
    }

    /**
     * **The assertion §5.6's `txn_count` rule exists for.**
     *
     * Three live debits were approved and one was binned, so two remain. Summing
     * the categories' counts would give three — groceries 2, home 1 — because
     * the split bill is filed under both. The header count must not be computed
     * that way, and this is what proves it is not.
     */
    @Test
    fun theTransactionCount_doesNotDoubleCountASplitBill() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        assertThat(snapshot.transactionCount).isEqualTo(2)
        assertThat(snapshot.categories.sumOf { it.transactionCount }).isEqualTo(3)
    }

    @Test
    fun categoryTotals_splitTheItemisedEntryAcrossBothCategories() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)
        val byName = snapshot.categories.associateBy { it.name }

        assertThat(byName.getValue("Groceries").amount.minor).isEqualTo(105_000L)
        assertThat(byName.getValue("Home").amount.minor).isEqualTo(40_000L)
        // Ranked, largest first -- the list is the content and it is ordered.
        assertThat(snapshot.categories.first().name).isEqualTo("Groceries")
    }

    @Test
    fun theCreditBookIsSeparate() = runBlocking<Unit> {
        seed()

        val credit = analytics.snapshot(LedgerType.CREDIT, window(), comparePrevious = false)

        assertThat(credit.total.minor).isEqualTo(500_000L)
        assertThat(credit.categories.map { it.name }).containsExactly("Salary")
    }

    @Test
    fun theTimeSeriesStacksByCategory_andItsTotalsMatchTheBuckets() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)
        val populated = snapshot.timeBuckets.filter { it.amount.minor > 0L }

        assertThat(populated).hasSize(1)
        assertThat(populated.single().amount.minor).isEqualTo(145_000L)
        // Stacked, not a single total bar -- that is what §5.6 asks for.
        assertThat(populated.single().byCategory.map { it.name })
            .containsExactly("Groceries", "Home")
        assertThat(populated.single().byCategory.sumOf { it.amount.minor }).isEqualTo(145_000L)
    }

    /**
     * **The series spans the whole window, gaps included.**
     *
     * Found on device: with two real entries in a 30-day view the chart drew
     * one bar across the entire plot, because SQL returns only days that had
     * spending. That reads as "this is the month" rather than "this is one day
     * of it" — the empty buckets are the information.
     */
    @Test
    fun theTimeSeriesCoversEveryBucket_notOnlyTheOnesWithSpending() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        assertThat(snapshot.timeBuckets).hasSize(AnalyticsRange.MONTH.bucketCount)
        assertThat(snapshot.timeBuckets.count { it.amount.minor > 0L }).isEqualTo(1)
        // Still the honest total: the zeros add nothing.
        assertThat(snapshot.timeBuckets.sumOf { it.amount.minor }).isEqualTo(145_000L)
        // Ordered and contiguous, so the axis reads left to right.
        assertThat(snapshot.timeBuckets.map { it.bucket })
            .isEqualTo((0 until AnalyticsRange.MONTH.bucketCount).toList())
    }

    @Test
    fun theUnfiledSentinelIsNamed_notBlank() = runBlocking<Unit> {
        // A line with no category lands on '' and must read as something.
        approve(
            30_000L,
            null,
            lines = listOf(NewLineItem("Sundries", Money(30_000L), categoryId = null)),
        )

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        assertThat(snapshot.categories.map { it.name }).contains("Uncategorised")
        assertThat(snapshot.categories.none { it.name.isBlank() }).isTrue()
    }

    @Test
    fun previousPeriodComparison_isOffByDefaultAndCorrectWhenOn() = runBlocking<Unit> {
        seed()

        val without = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)
        val with = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = true)

        assertThat(without.previousTotal).isNull()
        // Nothing was seeded in the preceding window, and zero is the honest
        // answer -- distinct from null, which means "we did not look".
        assertThat(with.previousTotal?.minor).isEqualTo(0L)
        assertThat(with.categories.first().previousAmount?.minor).isEqualTo(0L)
    }

    @Test
    fun anEmptyWindowReportsEmpty_ratherThanZeroRows() = runBlocking<Unit> {
        seed()

        // A window well before anything was seeded.
        val old = AnalyticsWindow.endingOn(today - 400, AnalyticsRange.MONTH)
        val snapshot = analytics.snapshot(LedgerType.DEBIT, old, comparePrevious = false)

        assertThat(snapshot.isEmpty).isTrue()
        assertThat(snapshot.total.minor).isEqualTo(0L)
        assertThat(snapshot.categories).isEmpty()
    }

    private fun window() = AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH)

    private suspend fun approve(
        amount: Long,
        categoryId: String?,
        ledger: LedgerType = LedgerType.DEBIT,
        lines: List<NewLineItem> = emptyList(),
    ): String {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = ledger,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(categoryId = categoryId),
                lineItems = lines,
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value.id
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
