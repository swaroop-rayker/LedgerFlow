package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.CaptureShare
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §5.6's composable filters, and the custom range.
 *
 * **The assertion that matters most is [theTwoPathsAgreeWhenNothingIsFiltered].**
 * A filter naming an entry's amount, source or note cannot be answered by
 * `daily_rollup`, so those reads fall back to `ledger_entry ⋈ line_item` — a
 * second copy of ADR-0018's grain expression. Two implementations of one
 * aggregate is exactly the drift ADR-0006 exists to prevent elsewhere, and
 * nothing else in the suite would notice if they diverged: each path is
 * internally consistent and only a comparison catches it.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsFilterTest {

    private val vault = LedgerTestVault("lf_filter_test")
    private lateinit var analytics: DefaultAnalyticsRepository

    private lateinit var groceries: Category
    private lateinit var home: Category
    private lateinit var zepto: Merchant
    private lateinit var swiggy: Merchant

    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        analytics = DefaultAnalyticsRepository(vault.session, Dispatchers.IO)
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        home = vault.categories.create(NewCategory(LedgerType.DEBIT, "Home")).success()
        zepto = vault.merchants.createOrGet("Zepto").success()
        swiggy = vault.merchants.createOrGet("Swiggy").success()
    }

    @After
    fun tearDown() = vault.close()

    private suspend fun seed() {
        approve(45_000L, groceries.id, zepto.id, note = "weekly shop")
        approve(20_000L, home.id, zepto.id, note = "lightbulbs")
        approve(80_000L, groceries.id, swiggy.id, note = "party food")
        approve(
            100_000L,
            null,
            swiggy.id,
            lines = listOf(
                NewLineItem("Rice", Money(60_000L), categoryId = groceries.id),
                NewLineItem("Kettle", Money(40_000L), categoryId = home.id),
            ),
        )
    }

    /**
     * **The two aggregates must agree.**
     *
     * With no filter both paths are reachable — the rollup by default, the base
     * tables by adding a filter that matches everything. If the grain
     * expressions ever drift, this is the only test that sees it.
     */
    @Test
    fun theTwoPathsAgreeWhenNothingIsFiltered() = runBlocking<Unit> {
        seed()

        val viaRollup = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        )
        // An amount floor of zero excludes nothing but forces the base-table
        // path, so the same data is aggregated both ways.
        val viaBaseTables = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(minAmount = Money(0L)),
        )

        assertThat(viaBaseTables.total.minor).isEqualTo(viaRollup.total.minor)
        assertThat(viaBaseTables.transactionCount).isEqualTo(viaRollup.transactionCount)
        assertThat(viaBaseTables.categories.associate { it.name to it.amount.minor })
            .isEqualTo(viaRollup.categories.associate { it.name to it.amount.minor })
        assertThat(viaBaseTables.merchants.associate { it.name to it.amount.minor })
            .isEqualTo(viaRollup.merchants.associate { it.name to it.amount.minor })
    }

    @Test
    fun filteringByCategory_narrowsEveryFigure() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(categoryIds = setOf(home.id)),
        )

        // ₹200 lightbulbs + the ₹400 kettle line. The grocery lines of the same
        // itemised entry are excluded, which is the line-grain filter working.
        assertThat(snapshot.total.minor).isEqualTo(60_000L)
        assertThat(snapshot.categories.map { it.name }).containsExactly("Home")
    }

    /**
     * A3's drill-down: the map is keyed by the **parent**, and it is populated.
     *
     * It was neither. `subcategoryTotalsFiltered` declared `DimensionTotalRow`,
     * which has no `category_id` field, so Room dropped the column the `SELECT`
     * was already fetching and the repository grouped by the only id left — the
     * subcategory's. `AnalyticsScreen` looks the map up by category id, so every
     * lookup missed and tapping a category expanded to nothing, always.
     *
     * Nothing caught it because the map was still *shaped* right: a non-empty
     * `Map<String, List<DimensionTotal>>` with correct totals under wrong keys.
     * This asserts the key, which is the part that was wrong.
     */
    @Test
    fun theSubcategoryBreakdownIsKeyedByItsParentCategory() = runBlocking<Unit> {
        val rice = vault.categories.create(
            NewCategory(LedgerType.DEBIT, "Rice", parentId = groceries.id),
        ).success()
        val dairy = vault.categories.create(
            NewCategory(LedgerType.DEBIT, "Dairy", parentId = groceries.id),
        ).success()
        approve(30_000L, groceries.id, zepto.id, subcategoryId = rice.id)
        approve(12_000L, groceries.id, zepto.id, subcategoryId = dairy.id)

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        )

        // Keyed by the parent, not by either child.
        assertThat(snapshot.subcategories.keys).containsExactly(groceries.id)
        assertThat(snapshot.subcategories.getValue(groceries.id).map { it.name })
            .containsExactly("Rice", "Dairy")
        assertThat(snapshot.subcategories.getValue(groceries.id).sumOf { it.amount.minor })
            .isEqualTo(42_000L)

        // And the screen's own lookup — by the id the category row carries —
        // finds them, which is the thing the user could not do.
        val groceryRow = snapshot.categories.single { it.id == groceries.id }
        assertThat(snapshot.subcategories[groceryRow.id]).hasSize(2)
    }

    /**
     * **The count binds every filter the total binds.**
     *
     * `distinctEntriesFromEntries` had no subcategory clause, so filtering to
     * one subcategory returned that subcategory's money beside the *window's*
     * entry count — on device, "₹12,300.00" over "3 transactions" for a single
     * entry. Two figures side by side that disagree is worse than either being
     * missing, because nothing on screen says which one to believe.
     */
    @Test
    fun filteringBySubcategory_narrowsTheCountAndTheTotalTogether() = runBlocking<Unit> {
        val rice = vault.categories.create(
            NewCategory(LedgerType.DEBIT, "Rice", parentId = groceries.id),
        ).success()
        seed()
        approve(30_000L, groceries.id, zepto.id, subcategoryId = rice.id)

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(subcategoryIds = setOf(rice.id)),
        )

        assertThat(snapshot.total.minor).isEqualTo(30_000L)
        assertThat(snapshot.transactionCount).isEqualTo(1)
    }

    /**
     * C1 — capture coverage sums to the total the screen is already showing.
     *
     * **The grain is the point.** Summing `e.amount_minor` would be defensible
     * in isolation and wrong here: the itemised entry in [seed] is ₹1,000 across
     * two lines, and an entry-grain sum would make coverage's denominator differ
     * from the headline total by nothing visible — the percentages would be
     * against a figure appearing nowhere else on the screen. This asserts the
     * two agree.
     */
    @Test
    fun captureCoverageSumsToTheSameTotalTheScreenShows() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        )

        assertThat(snapshot.captureCoverage.totalMinor).isEqualTo(snapshot.total.minor)
        assertThat(snapshot.captureCoverage.totalCount).isEqualTo(snapshot.transactionCount)
    }

    /**
     * The split is read from `ledger_entry.source`, per bucket.
     *
     * `approve` writes MANUAL by default, so a captured entry has to be asked
     * for explicitly — which is also the shape of the real pipeline, where only
     * the ingest path sets anything else.
     */
    @Test
    fun captureCoverageSplitsAutomaticFromTypedByHand() = runBlocking<Unit> {
        approve(60_000L, groceries.id, zepto.id, source = EntrySource.NOTIFICATION)
        approve(20_000L, groceries.id, zepto.id, source = EntrySource.SMS)
        approve(20_000L, home.id, zepto.id, source = EntrySource.MANUAL)

        val coverage = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        ).captureCoverage

        assertThat(coverage.automatic.amount.minor).isEqualTo(80_000L)
        assertThat(coverage.automatic.count).isEqualTo(2)
        assertThat(coverage.manual.amount.minor).isEqualTo(20_000L)
        // 80,000 of 100,000 by value; 2 of 3 by count. They disagree, which is
        // why C1 reports both.
        assertThat(coverage.automaticPercentByValue).isEqualTo(80)
        assertThat(coverage.automaticPercentByCount).isEqualTo(67)
    }

    /**
     * **Coverage narrows with every other figure on the screen.**
     *
     * The filter sheet promises "Narrow every figure on this screen", so a
     * section quietly exempting itself would make that copy a lie — and the
     * useful question ("how much of my *Food* spending is captured?") is the
     * same mechanism.
     */
    @Test
    fun captureCoverageHonoursTheFilters() = runBlocking<Unit> {
        approve(60_000L, groceries.id, zepto.id, source = EntrySource.NOTIFICATION)
        approve(40_000L, home.id, zepto.id, source = EntrySource.MANUAL)

        val coverage = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(categoryIds = setOf(groceries.id)),
        ).captureCoverage

        assertThat(coverage.totalMinor).isEqualTo(60_000L)
        assertThat(coverage.automaticPercentByValue).isEqualTo(100)
    }

    /**
     * C2 — the merchant typed by hand three times is named; the captured one is
     * not.
     */
    @Test
    fun theParserGapListNamesMerchantsTypedByHand() = runBlocking<Unit> {
        repeat(3) { approve(10_000L, groceries.id, zepto.id, source = EntrySource.MANUAL) }
        repeat(3) { approve(10_000L, groceries.id, swiggy.id, source = EntrySource.NOTIFICATION) }

        val gaps = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        ).parserGaps

        assertThat(gaps.map { it.name }).containsExactly("Zepto")
        assertThat(gaps.single().manualCount).isEqualTo(3)
        assertThat(gaps.single().totalCount).isEqualTo(3)
        assertThat(gaps.single().manualAmount.minor).isEqualTo(30_000L)
    }

    /**
     * **An entry with no merchant is not a gap.**
     *
     * A parser rule cannot target the absence of a payee, so an "Unfiled" row
     * would be the one line on this list nobody could act on — and `seed()`
     * alone produces enough unfiled spend to put it at the top.
     */
    @Test
    fun entriesWithNoMerchantAreNotGaps() = runBlocking<Unit> {
        repeat(4) { approve(10_000L, groceries.id, merchantId = null) }

        val gaps = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        ).parserGaps

        assertThat(gaps).isEmpty()
    }

    /**
     * An imported entry counts as a gap, where C1 counts it as neither.
     *
     * The two surfaces read the same column for different questions: C1 asks
     * "did this arrive by itself", and an import did not arrive at all; C2 asks
     * "is the ruleset blind to this merchant", and an import is evidence that no
     * rule read it. Pinned because the divergence looks like an inconsistency
     * until you ask what each is for.
     */
    @Test
    fun animportedEntryIsAGapEvenThoughC1CallsItNeither() = runBlocking<Unit> {
        repeat(3) { approve(10_000L, groceries.id, zepto.id, source = EntrySource.IMPORT) }

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        )

        assertThat(snapshot.parserGaps.map { it.name }).containsExactly("Zepto")
        assertThat(snapshot.captureCoverage.imported.count).isEqualTo(3)
        assertThat(snapshot.captureCoverage.manual).isEqualTo(CaptureShare.Empty)
    }

    /**
     * D1 — both books reach the snapshot, and **neither figure moves the other**.
     *
     * Law 2's whole point. A netted implementation would still produce a
     * plausible-looking chart, so the assertion is that the debit total is
     * exactly what a debit-only read gives and the credit total is exactly what
     * was approved into the other book — no subtraction anywhere between them.
     */
    @Test
    fun bothBooksAppearInParallel_andNeitherNetsTheOther() = runBlocking<Unit> {
        val salary = vault.categories.create(
            NewCategory(LedgerType.CREDIT, "Salary"),
        ).success()
        approve(45_000L, groceries.id, zepto.id)
        approve(20_000L, home.id, zepto.id)
        approveCredit(500_000L, salary.id)

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters.None,
        )

        val credited = snapshot.parallelBooks.sumOf { it.credit.minor }
        val debited = snapshot.parallelBooks.sumOf { it.debit.minor }
        assertThat(credited).isEqualTo(500_000L)
        // Unchanged by the credit sitting beside it, and equal to the debit
        // screen's own headline total.
        assertThat(debited).isEqualTo(65_000L)
        assertThat(debited).isEqualTo(snapshot.total.minor)
    }

    /**
     * **D1 ignores the filters, and that is specific to D1.**
     *
     * §5.5 makes the two books' category trees disjoint, so a debit category
     * filter applied to the credit book matches nothing — and the one view whose
     * purpose is showing both books would show one, looking broken. Pinned
     * because "make every section honour the filters" is an obvious tidy-up that
     * would silently empty half of this chart.
     */
    @Test
    fun theParallelViewKeepsBothBooksEvenUnderADebitFilter() = runBlocking<Unit> {
        val salary = vault.categories.create(
            NewCategory(LedgerType.CREDIT, "Salary"),
        ).success()
        approve(45_000L, groceries.id, zepto.id)
        approveCredit(500_000L, salary.id)

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(categoryIds = setOf(groceries.id)),
        )

        assertThat(snapshot.parallelBooks.sumOf { it.credit.minor }).isEqualTo(500_000L)
    }

    @Test
    fun filteringByMerchant_narrowsEveryFigure() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(merchantIds = setOf(zepto.id)),
        )

        assertThat(snapshot.total.minor).isEqualTo(65_000L)
        assertThat(snapshot.merchants.map { it.name }).containsExactly("Zepto")
    }

    @Test
    fun anAmountRange_usesTheBaseTablesAndExcludesTheSmallEntries() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(minAmount = Money(50_000L)),
        )

        // The ₹800 and ₹1,000 entries only: two of the four.
        assertThat(snapshot.transactionCount).isEqualTo(2)
        assertThat(snapshot.total.minor).isEqualTo(180_000L)
    }

    @Test
    fun aTextSearch_matchesNotesAndMerchantsAndItemNames() = runBlocking<Unit> {
        seed()

        val byNote = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(query = "lightbulb"),
        )
        val byMerchant = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(query = "swiggy"),
        )
        val byItem = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(query = "kettle"),
        )

        assertThat(byNote.transactionCount).isEqualTo(1)
        assertThat(byMerchant.transactionCount).isEqualTo(2)
        assertThat(byItem.transactionCount).isEqualTo(1)
    }

    /**
     * A `%` in the search text is a character, not a wildcard.
     *
     * Without `ESCAPE` in the SQL and escaping in the pattern, one `%` turns a
     * search into a match-all and the user gets everything back with no
     * indication why.
     */
    @Test
    fun aWildcardInTheSearchTextIsMatchedLiterally() = runBlocking<Unit> {
        seed()
        approve(30_000L, groceries.id, zepto.id, note = "50% off")

        val literal = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(query = "50%"),
        )
        val matchAll = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(query = "%"),
        )

        assertThat(literal.transactionCount).isEqualTo(1)
        // A bare "%" matches only entries whose text really contains one.
        assertThat(matchAll.transactionCount).isEqualTo(1)
    }

    @Test
    fun filteringBySource_usesTheBaseTables() = runBlocking<Unit> {
        seed()

        val manual = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(sources = setOf(EntrySource.MANUAL)),
        )
        val sms = analytics.snapshot(
            LedgerType.DEBIT, window(), comparePrevious = false,
            filters = AnalyticsFilters(sources = setOf(EntrySource.SMS)),
        )

        assertThat(manual.transactionCount).isEqualTo(4)
        assertThat(sms.transactionCount).isEqualTo(0)
    }

    @Test
    fun combinedFilters_narrowTogether() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            window(),
            comparePrevious = false,
            filters = AnalyticsFilters(
                categoryIds = setOf(groceries.id),
                merchantIds = setOf(swiggy.id),
                minAmount = Money(50_000L),
            ),
        )

        // Groceries AND Swiggy AND ≥ ₹500: the ₹800 party food, plus the ₹600
        // rice line of the ₹1,000 itemised entry.
        assertThat(snapshot.total.minor).isEqualTo(140_000L)
    }

    // ── Custom range ───────────────────────────────────────────────────────

    @Test
    fun aCustomWindowSpansExactlyTheDatesGiven() {
        val window = AnalyticsWindow.custom(from = today - 9, to = today)

        assertThat(window.spanDays).isEqualTo(10)
        assertThat(window.bucketCount).isEqualTo(10)
        assertThat(window.bucketDays).isEqualTo(1)
    }

    /**
     * A wide custom range widens its buckets rather than handing the chart
     * thousands of columns (§11).
     */
    @Test
    fun awideCustomWindowKeepsItsBucketCountBounded() {
        val tenYears = AnalyticsWindow.custom(from = today - 3_650, to = today)

        assertThat(tenYears.bucketCount).isAtMost(AnalyticsWindow.MAX_BUCKETS)
        assertThat(tenYears.bucketDays).isGreaterThan(1)
    }

    /** Reversed dates are ordered rather than producing a negative span. */
    @Test
    fun areversedCustomWindowIsOrdered() {
        val window = AnalyticsWindow.custom(from = today, to = today - 9)

        assertThat(window.from).isEqualTo(today - 9)
        assertThat(window.to).isEqualTo(today)
    }

    @Test
    fun aCustomWindowReadsTheLedger() = runBlocking<Unit> {
        seed()

        val snapshot = analytics.snapshot(
            LedgerType.DEBIT,
            AnalyticsWindow.custom(from = today - 2, to = today),
            comparePrevious = false,
        )

        assertThat(snapshot.total.minor).isEqualTo(245_000L)
        assertThat(snapshot.timeBuckets).hasSize(3)
    }

    private fun window() = AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH)

    private suspend fun approve(
        amount: Long,
        categoryId: String?,
        merchantId: String? = null,
        note: String? = null,
        lines: List<NewLineItem> = emptyList(),
        subcategoryId: String? = null,
        source: EntrySource = EntrySource.MANUAL,
    ) {
        val occurredAt = LocalDate.ofEpochDay(today.toLong())
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(
                    categoryId = categoryId,
                    subcategoryId = subcategoryId,
                    merchantId = merchantId,
                ),
                note = note,
                lineItems = lines,
                origin = EntryOrigin(source),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    /** A credit approval, for D1. The other book, reached the same way. */
    private suspend fun approveCredit(amount: Long, categoryId: String) {
        val occurredAt = LocalDate.ofEpochDay(today.toLong())
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.CREDIT,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(categoryId = categoryId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
