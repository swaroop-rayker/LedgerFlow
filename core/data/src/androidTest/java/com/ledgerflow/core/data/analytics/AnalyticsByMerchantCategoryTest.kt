package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.taxonomy.DefaultMerchantCategoryRepository
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.MerchantCategoryBreakdown
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
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
 * A11 against the real vault (V2-6 PR 4): the section adds up to the page
 * total in both books, re-filing a merchant moves its history, and the
 * merchant-category filter narrows every figure.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsByMerchantCategoryTest {

    private val vault = LedgerTestVault("lf_merchant_category_analytics_test")
    private lateinit var analytics: DefaultAnalyticsRepository
    private lateinit var kinds: DefaultMerchantCategoryRepository

    private lateinit var food: Category
    private lateinit var salary: Category
    private lateinit var zepto: Merchant
    private lateinit var shell: Merchant
    private lateinit var corner: Merchant
    private lateinit var groceries: MerchantCategory
    private lateinit var fuel: MerchantCategory

    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        analytics = DefaultAnalyticsRepository(vault.session, Dispatchers.IO)
        kinds = DefaultMerchantCategoryRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
        food = vault.categories.create(NewCategory(LedgerType.DEBIT, "Food")).success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
        zepto = vault.merchants.createOrGet("Zepto").success()
        shell = vault.merchants.createOrGet("Shell").success()
        corner = vault.merchants.createOrGet("Corner Grocer").success()
        groceries = kinds.create("Groceries").success()
        fuel = kinds.create("Fuel").success()

        kinds.setCategory(listOf(zepto.id), groceries.id).success()
        kinds.setCategory(listOf(shell.id), fuel.id).success()
        // Corner Grocer stays Uncategorised.

        approve(LedgerType.DEBIT, 45_000L, zepto.id)
        approve(LedgerType.DEBIT, 30_000L, shell.id)
        approve(LedgerType.DEBIT, 12_000L, corner.id)
        approve(LedgerType.DEBIT, 5_000L, merchantId = null)
        approve(LedgerType.CREDIT, 200_000L, merchantId = null)
    }

    @After
    fun tearDown() = vault.close()

    /** §5.4: Uncategorised and No merchant included, the section is the page, in each book. */
    @Test
    fun theSectionAddsUpToThePageTotal_inBothBooks() = runBlocking<Unit> {
        LedgerType.entries.forEach { book ->
            val snapshot = snapshot(book)
            val section = snapshot.merchantCategories.totals.sumOf { it.amount.minor }
            assertThat(section).isEqualTo(snapshot.total.minor)
        }
    }

    @Test
    fun spendingLandsInEachMerchantsCategory() = runBlocking<Unit> {
        val totals = snapshot(LedgerType.DEBIT).merchantCategories.totals
        assertThat(totals.map { it.name to it.amount.minor }).containsExactly(
            "Groceries" to 45_000L,
            "Fuel" to 30_000L,
            "Uncategorised" to 12_000L,
            MerchantCategoryBreakdown.NO_MERCHANT to 5_000L,
        ).inOrder()
    }

    /** Income has no merchants here: the credit book is all No merchant, never mixed with spending (Law 2). */
    @Test
    fun theIncomeBook_isItsOwn() = runBlocking<Unit> {
        val totals = snapshot(LedgerType.CREDIT).merchantCategories.totals
        assertThat(totals.map { it.id to it.amount.minor })
            .containsExactly(MerchantCategoryBreakdown.NO_MERCHANT_ID to 200_000L)
    }

    /** §5.4's retroactive rule: filing Corner Grocer under Groceries moves its past spending there. */
    @Test
    fun refilingAMerchant_movesItsHistory() = runBlocking<Unit> {
        kinds.setCategory(listOf(corner.id), groceries.id).success()

        val totals = snapshot(LedgerType.DEBIT).merchantCategories.totals.associate { it.id to it.amount.minor }
        assertThat(totals[groceries.id]).isEqualTo(57_000L)
        assertThat(totals).doesNotContainKey(MerchantCategoryBreakdown.UNCATEGORISED_ID)
    }

    @Test
    fun filteringByMerchantCategory_narrowsEveryFigure() = runBlocking<Unit> {
        val snapshot = snapshot(LedgerType.DEBIT, AnalyticsFilters(merchantCategoryIds = setOf(groceries.id)))

        assertThat(snapshot.total.minor).isEqualTo(45_000L)
        assertThat(snapshot.merchants.map { it.name }).containsExactly("Zepto")
        assertThat(snapshot.categories.map { it.amount.minor }).containsExactly(45_000L)
    }

    @Test
    fun filteringByUncategorised_isTheUnfiledMerchants() = runBlocking<Unit> {
        val filters = AnalyticsFilters(merchantCategoryIds = setOf(MerchantCategoryBreakdown.UNCATEGORISED_ID))
        assertThat(snapshot(LedgerType.DEBIT, filters).total.minor).isEqualTo(12_000L)
    }

    private suspend fun snapshot(
        book: LedgerType,
        filters: AnalyticsFilters = AnalyticsFilters.None,
    ): AnalyticsSnapshot = analytics.snapshot(
        book,
        AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH),
        comparePrevious = true,
        filters,
    )

    private suspend fun approve(book: LedgerType, amount: Long, merchantId: String?) {
        val occurredAt = LocalDate.ofEpochDay(today.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = book,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(
                    categoryId = if (book == LedgerType.DEBIT) food.id else salary.id,
                    merchantId = merchantId,
                ),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
