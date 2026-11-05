package com.ledgerflow.core.data.ledger

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.taxonomy.DefaultMerchantCategoryRepository
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
 * The Ledger narrowed to merchant categories, against the real vault (V2-6,
 * V2-PLAN §5.3): the chosen categories' merchants as filed now, Uncategorised
 * including a merchant whose category was hidden, no-merchant entries never,
 * one book at a time, and re-filing a merchant moving its entries.
 */
@RunWith(AndroidJUnit4::class)
class LedgerMerchantCategoryFilterReadTest {

    private val vault = LedgerTestVault("lf_ledger_merchant_filter_test")
    private lateinit var pages: DefaultMerchantEntriesRepository
    private lateinit var kinds: DefaultMerchantCategoryRepository

    private lateinit var food: Category
    private lateinit var salary: Category
    private lateinit var zepto: Merchant
    private lateinit var shell: Merchant
    private lateinit var corner: Merchant
    private lateinit var oldShop: Merchant
    private lateinit var groceries: MerchantCategory
    private lateinit var fuel: MerchantCategory

    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()
    private val since: Int = today - WINDOW

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        pages = DefaultMerchantEntriesRepository(vault.session)
        kinds = DefaultMerchantCategoryRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
        food = vault.categories.create(NewCategory(LedgerType.DEBIT, "Food")).success()
        salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()
        zepto = vault.merchants.createOrGet("Zepto").success()
        shell = vault.merchants.createOrGet("Shell").success()
        corner = vault.merchants.createOrGet("Corner Grocer").success()
        oldShop = vault.merchants.createOrGet("Old Shop").success()
        groceries = kinds.create("Groceries").success()
        fuel = kinds.create("Fuel").success()
        val gone = kinds.create("Gone").success()

        kinds.setCategory(listOf(zepto.id), groceries.id).success()
        kinds.setCategory(listOf(shell.id), fuel.id).success()
        kinds.setCategory(listOf(oldShop.id), gone.id).success()
        // Corner Grocer stays unfiled. Old Shop's category is hidden *after*
        // filing, by writing its deleted_at directly, so the merchant still
        // points at it: the case a hide that moved nothing would leave.
        vault.database.openHelper.writableDatabase
            .execSQL("UPDATE merchant_category SET deleted_at = 1 WHERE id = ?", arrayOf<Any>(gone.id))

        approve(LedgerType.DEBIT, 45_000L, zepto.id, "zepto")
        approve(LedgerType.DEBIT, 30_000L, shell.id, "shell")
        approve(LedgerType.DEBIT, 12_000L, corner.id, "corner")
        approve(LedgerType.DEBIT, 8_000L, oldShop.id, "old")
        approve(LedgerType.DEBIT, 5_000L, merchantId = null, note = "cash")
        approve(LedgerType.CREDIT, 20_000L, zepto.id, "refund")
        approve(LedgerType.DEBIT, 9_000L, zepto.id, "too old", daysAgo = WINDOW + 1)
    }

    @After
    fun tearDown() = vault.close()

    private suspend fun notes(book: LedgerType, chosen: Set<String>): List<String?> =
        pages.observeEntriesInMerchantCategories(book, chosen, since).asSnapshot().map { it.note }

    @Test
    fun aCategory_listsItsMerchantsEntries_inTheWindow() = runBlocking<Unit> {
        assertThat(notes(LedgerType.DEBIT, setOf(groceries.id))).containsExactly("zepto")
        assertThat(notes(LedgerType.DEBIT, setOf(groceries.id, fuel.id))).containsExactly("zepto", "shell")
    }

    /** Unfiled, or filed under a category no longer live: as Organise and Analytics count them. */
    @Test
    fun uncategorised_isTheUnfiledMerchants_andHiddenCategories() = runBlocking<Unit> {
        val unfiled = notes(LedgerType.DEBIT, setOf(MerchantCategoryBreakdown.UNCATEGORISED_ID))
        assertThat(unfiled).containsExactly("corner", "old")
    }

    /** An entry with no merchant has no kind of place: no choice matches it. */
    @Test
    fun anEntryWithNoMerchant_neverMatches() = runBlocking<Unit> {
        val all = setOf(groceries.id, fuel.id, MerchantCategoryBreakdown.UNCATEGORISED_ID)
        assertThat(notes(LedgerType.DEBIT, all)).doesNotContain("cash")
    }

    /** Law 2: the same merchant's income stays in the income book. */
    @Test
    fun eachBook_isItsOwn() = runBlocking<Unit> {
        assertThat(notes(LedgerType.CREDIT, setOf(groceries.id))).containsExactly("refund")
        assertThat(notes(LedgerType.DEBIT, setOf(groceries.id))).doesNotContain("refund")
    }

    /** The join is read now: filing Corner Grocer under Groceries moves its entries into the filter. */
    @Test
    fun refilingAMerchant_movesItsEntries() = runBlocking<Unit> {
        kinds.setCategory(listOf(corner.id), groceries.id).success()

        assertThat(notes(LedgerType.DEBIT, setOf(groceries.id))).containsExactly("zepto", "corner")
    }

    private suspend fun approve(
        book: LedgerType,
        amount: Long,
        merchantId: String?,
        note: String,
        daysAgo: Int = 0,
    ) {
        val day = LocalDate.ofEpochDay((today - daysAgo).toLong())
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = book,
                amount = Money(amount),
                occurredAt = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                assignment = EntryAssignment(
                    categoryId = if (book == LedgerType.DEBIT) food.id else salary.id,
                    merchantId = merchantId,
                ),
                note = note,
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private companion object {
        const val WINDOW = 30
    }
}
