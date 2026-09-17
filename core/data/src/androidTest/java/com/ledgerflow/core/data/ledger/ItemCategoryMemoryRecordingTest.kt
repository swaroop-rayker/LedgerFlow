package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.entity.ItemCategoryMemoryEntity
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 27 of §5.3: an approval records what each line was filed under, so the
 * next bill from the same shop can suggest it (`item_category_memory`).
 *
 * Against the real vault rather than a fake DAO, because two of the properties
 * are the database's: the count accumulates through `ON CONFLICT DO UPDATE`,
 * and a refused approval must leave no memory behind — which holds only if the
 * recording is inside the approval's transaction.
 */
@RunWith(AndroidJUnit4::class)
class ItemCategoryMemoryRecordingTest {

    private val vault = LedgerTestVault("lf_item_category_memory_test")

    private lateinit var groceries: Category
    private lateinit var dairy: Category
    private lateinit var electronics: Category
    private lateinit var salary: Category
    private lateinit var grocer: Merchant

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = category(LedgerType.DEBIT, "Groceries")
        dairy = category(LedgerType.DEBIT, "Dairy", parentId = groceries.id)
        electronics = category(LedgerType.DEBIT, "Electronics")
        salary = category(LedgerType.CREDIT, "Salary")
        grocer = vault.merchants.createOrGet("Corner Grocer").let {
            assertThat(it).isInstanceOf(TaxonomyResult.Success::class.java)
            (it as TaxonomyResult.Success).value
        }
    }

    @After
    fun tearDown() = vault.close()

    /** The step itself: each categorised item, keyed by merchant and normalised name. */
    @Test
    fun approve_recordsEachCategorisedItemLine() = runBlocking<Unit> {
        approve(
            merchantId = grocer.id,
            lines = listOf(
                NewLineItem("Milk 1L", Money(60_00L), categoryId = groceries.id, subcategoryId = dairy.id),
                NewLineItem("Kettle", Money(40_00L), categoryId = electronics.id),
            ),
        )

        assertThat(memory()).containsExactly(
            ItemCategoryMemoryEntity(grocer.id, "kettle", electronics.id, null, 1),
            ItemCategoryMemoryEntity(grocer.id, "milk 1l", groceries.id, dairy.id, 1),
        )
    }

    /** A second bill carrying the same item confirms the filing rather than duplicating it. */
    @Test
    fun approve_sameItemOnALaterBill_incrementsTheCount() = runBlocking<Unit> {
        repeat(2) {
            approve(merchantId = grocer.id, lines = listOf(NewLineItem("Milk 1L", Money(100_00L), categoryId = groceries.id)))
        }

        assertThat(memory().single().hitCount).isEqualTo(2)
    }

    /**
     * Two lines of one bill for the same item — ordinary on a receipt — are two
     * confirmations. A read-then-write would have lost one; the upsert does not.
     */
    @Test
    fun approve_sameItemTwiceOnOneBill_countsBoth() = runBlocking<Unit> {
        approve(
            merchantId = grocer.id,
            lines = listOf(
                NewLineItem("Milk 1L", Money(50_00L), categoryId = groceries.id),
                NewLineItem("MILK 1L", Money(50_00L), categoryId = groceries.id),
            ),
        )

        assertThat(memory().single().hitCount).isEqualTo(2)
    }

    /** Refiling replaces the category and keeps the accumulated count (the DAO's KDoc). */
    @Test
    fun approve_itemRefiledUnderAnotherCategory_replacesItAndKeepsTheCount() = runBlocking<Unit> {
        approve(merchantId = grocer.id, lines = listOf(NewLineItem("Batteries", Money(100_00L), categoryId = groceries.id)))
        approve(merchantId = grocer.id, lines = listOf(NewLineItem("Batteries", Money(100_00L), categoryId = electronics.id)))

        val row = memory().single()
        assertThat(row.categoryId).isEqualTo(electronics.id)
        assertThat(row.hitCount).isEqualTo(2)
    }

    /**
     * **The owner's rule.** A line with no category of its own was filed under
     * the entry's, subcategory included — on a single-category bill, that is
     * the filing of each item on it.
     */
    @Test
    fun approve_lineWithoutACategory_recordsTheEntrysCategoryAndSubcategory() = runBlocking<Unit> {
        approve(
            merchantId = grocer.id,
            assignment = EntryAssignment(categoryId = groceries.id, subcategoryId = dairy.id),
            lines = listOf(NewLineItem("Paneer 200G", Money(100_00L))),
        )

        assertThat(memory()).containsExactly(
            ItemCategoryMemoryEntity(grocer.id, "paneer 200g", groceries.id, dairy.id, 1),
        )
    }

    /**
     * The two levels never mix. A line filed under its own category keeps its
     * own (absent) subcategory; borrowing the entry's would pair `Dairy` with
     * `Electronics`, a combination the approval itself refuses.
     */
    @Test
    fun approve_lineWithItsOwnCategory_doesNotBorrowTheEntrysSubcategory() = runBlocking<Unit> {
        approve(
            merchantId = grocer.id,
            assignment = EntryAssignment(categoryId = groceries.id, subcategoryId = dairy.id),
            lines = listOf(NewLineItem("Kettle", Money(100_00L), categoryId = electronics.id)),
        )

        assertThat(memory()).containsExactly(
            ItemCategoryMemoryEntity(grocer.id, "kettle", electronics.id, null, 1),
        )
    }

    /** Tax and the unallocated remainder are rows of the bill, not products. */
    @Test
    fun approve_nonItemLines_areNotRecorded() = runBlocking<Unit> {
        approve(
            amount = 100_00L,
            merchantId = grocer.id,
            assignment = EntryAssignment(categoryId = groceries.id),
            lines = listOf(
                NewLineItem("Rice 1KG", Money(60_00L)),
                NewLineItem("CGST 2.5%", Money(10_00L), kind = LineItemKind.TAX),
                // 30.00 short, so an UNALLOCATED row is written as well.
            ),
        ).also { entry -> assertThat(entry.lineItems.map { it.kind }).contains(LineItemKind.UNALLOCATED) }

        assertThat(memory().map { it.normalizedItem }).containsExactly("rice 1kg")
    }

    /** Itemising is not filing: with no category anywhere there is nothing to learn. */
    @Test
    fun approve_uncategorisedEverywhere_recordsNothing() = runBlocking<Unit> {
        approve(merchantId = grocer.id, lines = listOf(NewLineItem("Rice 1KG", Money(100_00L))))

        assertThat(memory()).isEmpty()
    }

    /** No merchant is the `''` key, which is what lets such a row accumulate hits. */
    @Test
    fun approve_withNoMerchant_recordsUnderTheEmptyKey() = runBlocking<Unit> {
        repeat(2) {
            approve(merchantId = null, lines = listOf(NewLineItem("Rice 1KG", Money(100_00L), categoryId = groceries.id)))
        }

        assertThat(memory()).containsExactly(
            ItemCategoryMemoryEntity("", "rice 1kg", groceries.id, null, 2),
        )
    }

    /**
     * An empty key would pool every such item at a merchant into one
     * suggestion. `ItemNameNormalizer` keeps `a-z0-9` only, so a Devanagari
     * name is the real case, not a contrived one.
     */
    @Test
    fun approve_nameThatNormalisesToNothing_isNotRecorded() = runBlocking<Unit> {
        approve(
            merchantId = grocer.id,
            lines = listOf(
                NewLineItem("शक्कर", Money(50_00L), categoryId = groceries.id),
                NewLineItem("***", Money(50_00L), categoryId = groceries.id),
            ),
        )

        assertThat(memory()).isEmpty()
    }

    /**
     * A refused approval teaches nothing — the line that was fine included.
     * This is the transaction's property, and the reason recording lives
     * inside it.
     */
    @Test
    fun approve_refused_recordsNothing() = runBlocking<Unit> {
        val result = vault.ledger.approve(
            request(
                merchantId = grocer.id,
                lines = listOf(
                    NewLineItem("Rice 1KG", Money(50_00L), categoryId = groceries.id),
                    NewLineItem("Payday", Money(50_00L), categoryId = salary.id),
                ),
            ),
        )

        assertThat(result).isInstanceOf(LedgerResult.Failure::class.java)
        assertThat(memory()).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun memory(): List<ItemCategoryMemoryEntity> =
        vault.session.requireDatabase().itemCategoryMemoryDao().all()

    private suspend fun approve(
        amount: Long? = null,
        merchantId: String?,
        assignment: EntryAssignment = EntryAssignment(),
        lines: List<NewLineItem>,
    ): LedgerEntry {
        val result = vault.ledger.approve(request(amount, merchantId, assignment, lines))
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value
    }

    private fun request(
        amount: Long? = null,
        merchantId: String?,
        assignment: EntryAssignment = EntryAssignment(),
        lines: List<NewLineItem>,
    ) = ApprovalRequest(
        ledger = LedgerType.DEBIT,
        amount = amount?.let(::Money) ?: Money.sum(lines.map { it.total }),
        occurredAt = 1_700_000_000_000L,
        assignment = assignment.copy(merchantId = merchantId),
        lineItems = lines,
    )

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
}
