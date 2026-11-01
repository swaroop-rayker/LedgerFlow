package com.ledgerflow.core.data.analytics

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.taxonomy.DefaultMerchantCategoryRepository
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §11: Analytics over five years in **under 300 ms**, re-measured for
 * V2-6 PR 4, whose merchant-category section adds a read of last window's
 * merchant totals and an in-memory regroup to every snapshot.
 *
 * The row in §11 says "instrumented timing test", and until now the figure was
 * measured by hand at P3. This seeds what the bench vault holds (five years,
 * up to two purchases a day across a dozen merchants, half of them filed under
 * merchant categories, plus monthly rent and salary) and times the whole
 * snapshot the screen asks for: 5Y, comparison on, unfiltered and filtered by
 * merchant category. The median of five runs after one warm-up, because the
 * first read in a process pays SQLCipher's page cache.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsFiveYearTimingTest {

    private val vault = LedgerTestVault("lf_five_year_timing_test")
    private lateinit var analytics: DefaultAnalyticsRepository
    private val today: LocalDate = LocalDate.of(2026, 10, 31)
    private lateinit var groceriesId: String

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        analytics = DefaultAnalyticsRepository(vault.session, Dispatchers.IO)
        val kinds = DefaultMerchantCategoryRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
        val spending = listOf("Groceries", "Dining", "Transport", "Shopping").map { name ->
            vault.categories.create(NewCategory(LedgerType.DEBIT, name)).success().id
        }
        val salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success().id
        val shops = (1..MERCHANTS).map { vault.merchants.createOrGet("Shop $it").success().id }
        val filed = listOf("Groceries", "Restaurants", "Travel").map { kinds.create(it).success().id }
        groceriesId = filed.first()
        shops.take(MERCHANTS / 2).forEachIndexed { i, shop ->
            kinds.setCategory(listOf(shop), filed[i % filed.size]).success()
        }

        val random = Random(SEED)
        var day = today.minusYears(YEARS)
        while (!day.isAfter(today)) {
            repeat(random.nextInt(MAX_PER_DAY + 1)) {
                val amount = random.nextLong(MIN_MINOR, MAX_MINOR)
                approve(day, LedgerType.DEBIT, amount, spending.random(random), shops.random(random))
            }
            if (day.dayOfMonth == 1) {
                approve(day, LedgerType.DEBIT, RENT_MINOR, spending.first(), merchantId = null)
                approve(day, LedgerType.CREDIT, SALARY_MINOR, salary, merchantId = null)
            }
            day = day.plusDays(1)
        }
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun fiveYears_unfiltered_isUnderBudget() = runBlocking<Unit> {
        assertUnderBudget("unfiltered", AnalyticsFilters.None)
    }

    @Test
    fun fiveYears_byMerchantCategory_isUnderBudget() = runBlocking<Unit> {
        assertUnderBudget("merchant category", AnalyticsFilters(merchantCategoryIds = setOf(groceriesId)))
    }

    private suspend fun assertUnderBudget(label: String, filters: AnalyticsFilters) {
        val window = AnalyticsWindow.endingOn(today.toEpochDay().toInt(), AnalyticsRange.FIVE_YEARS)
        suspend fun once(): Long {
            val startedAt = System.nanoTime()
            analytics.snapshot(LedgerType.DEBIT, window, comparePrevious = true, filters = filters)
            return (System.nanoTime() - startedAt) / NANOS_PER_MS
        }
        once()
        val runs = List(RUNS) { once() }.sorted()
        val median = runs[RUNS / 2]
        Log.i(TAG, "5Y $label: median $median ms, runs $runs")
        assertWithMessage("5Y snapshot ($label), runs $runs ms").that(median).isLessThan(BUDGET_MS)
    }

    private suspend fun approve(
        day: LocalDate,
        book: LedgerType,
        minor: Long,
        categoryId: String,
        merchantId: String?,
    ) {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = book,
                amount = Money(minor),
                occurredAt = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                assignment = EntryAssignment(categoryId = categoryId, merchantId = merchantId),
            ),
        )
        check(result is LedgerResult.Success) { "Seeding failed on $day: $result" }
    }

    private fun <T> TaxonomyResult<T>.success(): T = (this as TaxonomyResult.Success).value

    private companion object {
        const val TAG = "AnalyticsTiming"
        const val YEARS = 5L
        const val MERCHANTS = 12
        const val MAX_PER_DAY = 2
        const val MIN_MINOR = 5_000L
        const val MAX_MINOR = 500_000L
        const val RENT_MINOR = 2_500_000L
        const val SALARY_MINOR = 9_000_000L
        const val SEED = 42
        const val RUNS = 5
        const val BUDGET_MS = 300L
        const val NANOS_PER_MS = 1_000_000L
    }
}
