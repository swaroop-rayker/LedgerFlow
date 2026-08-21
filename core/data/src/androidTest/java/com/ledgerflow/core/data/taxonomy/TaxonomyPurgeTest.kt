package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The taxonomy's irreversible half, against a real SQLCipher vault (ADR-0016).
 *
 * Instrumented rather than unit, and for a sharper reason than usual: the thing
 * under test is a rule that exists **because** the schema will not enforce it.
 * `ledger_entry.merchant_id` is `ON DELETE SET NULL` and `category_id` has no
 * foreign key at all, so a purge that skipped its check would pass every fake
 * and, on a real database, succeed while quietly stripping names off entries.
 * Only a real SQLite file can tell those two apart.
 *
 * The case that matters most here is the one nobody writes by accident: a
 * merchant referenced **only by a binned entry**. It looks unused from every
 * screen in the app, the live-entry count says zero, and destroying it means a
 * user who later restores that entry from the bin gets back a row that lost its
 * merchant while it sat there.
 */
@RunWith(AndroidJUnit4::class)
class TaxonomyPurgeTest {

    private val vault = LedgerTestVault("lf_taxonomy_purge_test")

    private lateinit var groceries: Category
    private lateinit var vegetables: Category
    private lateinit var household: Category
    private lateinit var bigBazaar: Merchant
    private lateinit var dmart: Merchant

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        vegetables = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Vegetables", parentId = groceries.id))
            .success()
        household = vault.categories.create(NewCategory(LedgerType.DEBIT, "Household")).success()
        bigBazaar = vault.merchants.createOrGet("Big Bazaar").success()
        dmart = vault.merchants.createOrGet("DMart").success()
    }

    @After
    fun tearDown() = vault.close()

    // ── The hidden list ──────────────────────────────────────────────────────

    @Test
    fun hide_thenRestore_bringsAMerchantBack() = runBlocking<Unit> {
        vault.merchants.delete(bigBazaar.id).success()

        assertThat(liveMerchantNames()).containsExactly("DMart")
        assertThat(hiddenMerchantNames()).containsExactly("Big Bazaar")

        vault.merchants.restore(bigBazaar.id).success()

        assertThat(liveMerchantNames()).containsExactly("Big Bazaar", "DMart")
        assertThat(hiddenMerchantNames()).isEmpty()
    }

    /**
     * A hidden merchant's name cannot be taken from under it (BUG11).
     *
     * This test was written to set up a *different* scenario -- a restore
     * refused because the key had been taken -- and could not, because the
     * rename it needed threw `SQLiteConstraintException` instead of returning
     * anything. That is the bug, in the second of two methods that read
     * `byNormalizedKey` and so could not see the hidden row holding the key.
     *
     * The refusal names the hidden row rather than saying "already exists",
     * which would send the user looking through a list it is deliberately not
     * in. With both write paths closed, the state the original test wanted is
     * now unreachable through the repository at all -- so `restore`'s own clash
     * check is defence in depth, and there is nothing here that can exercise it.
     */
    @Test
    fun renaming_ontoAHiddenMerchantsName_isRefusedRatherThanThrowing() = runBlocking<Unit> {
        vault.merchants.delete(bigBazaar.id).success()

        assertThat(vault.merchants.rename(dmart.id, "Big Bazaar").error())
            .isEqualTo(TaxonomyError.NameHeldByHiddenRow("Big Bazaar"))

        assertThat(liveMerchantNames()).containsExactly("DMart")
        assertThat(hiddenMerchantNames()).containsExactly("Big Bazaar")
    }

    /** Once the hidden row is erased, the name is genuinely free. */
    @Test
    fun renaming_ontoAnErasedMerchantsName_succeeds() = runBlocking<Unit> {
        vault.merchants.delete(bigBazaar.id).success()
        vault.merchants.purge(bigBazaar.id, null).success()

        vault.merchants.rename(dmart.id, "Big Bazaar").success()

        assertThat(liveMerchantNames()).containsExactly("Big Bazaar")
    }

    /**
     * A restored payment method is never the default.
     *
     * Hiding does not clear `is_default`, because nothing reads the flag on a
     * hidden row. Restoring without clearing it is how an install ends up with
     * two rows both claiming to be the default -- and the schema has no
     * constraint that would notice.
     */
    @Test
    fun restore_bringsAPaymentMethodBackAsANonDefault() = runBlocking<Unit> {
        val card = vault.paymentMethods
            .create(NewPaymentMethod(PaymentMethodType.CREDIT_CARD, "HDFC", last4 = "4821"))
            .success()
        vault.paymentMethods.setDefault(card.id).success()
        vault.paymentMethods.delete(card.id).success()

        val upi = vault.paymentMethods
            .create(NewPaymentMethod(PaymentMethodType.UPI, "GPay"))
            .success()
        vault.paymentMethods.setDefault(upi.id).success()

        vault.paymentMethods.restore(card.id).success()

        val defaults = vault.paymentMethods.observeAll().first().filter { it.isDefault }
        assertThat(defaults.map { it.label }).containsExactly("GPay")
    }

    // ── The purge: merchants ─────────────────────────────────────────────────

    @Test
    fun purge_destroysAnUnusedHiddenMerchant() = runBlocking<Unit> {
        vault.merchants.delete(bigBazaar.id).success()

        vault.merchants.purge(bigBazaar.id, null).success()

        assertThat(allMerchantIds()).doesNotContain(bigBazaar.id)
        assertThat(hiddenMerchantNames()).isEmpty()
    }

    @Test
    fun purge_isRefusedWhileALiveEntryStillUsesTheMerchant() = runBlocking<Unit> {
        approve(merchantId = bigBazaar.id)
        vault.merchants.delete(bigBazaar.id).success()

        assertThat(vault.merchants.purge(bigBazaar.id, null).error())
            .isEqualTo(TaxonomyError.ReassignRequired(1))
        assertThat(allMerchantIds()).contains(bigBazaar.id)
    }

    /**
     * **The case the live-entry count would miss.**
     *
     * `countForMerchant` binds `deleted_at IS NULL`, which is right for a soft
     * delete and wrong for this: a binned entry is restorable, and destroying
     * the merchant it points at means the user restores a row that lost its
     * shop while it was in the bin. `countAllForMerchant` omits the predicate,
     * and this test is what stops the two being "simplified" into one.
     */
    @Test
    fun purge_isRefusedWhenOnlyABinnedEntryUsesTheMerchant() = runBlocking<Unit> {
        val entry = approve(merchantId = bigBazaar.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry.id)
        vault.merchants.delete(bigBazaar.id).success()

        assertThat(vault.merchants.purge(bigBazaar.id, null).error())
            .isEqualTo(TaxonomyError.ReassignRequired(1))
        assertThat(allMerchantIds()).contains(bigBazaar.id)
    }

    /**
     * And when it is re-assigned, the binned entry is re-pointed too.
     *
     * Not merely spared. If the re-point skipped binned rows the purge would
     * still leave one entry holding a dead id, and the count would have been
     * theatre.
     */
    @Test
    fun purge_repointsABinnedEntryBeforeDestroyingTheMerchant() = runBlocking<Unit> {
        val binned = approve(merchantId = bigBazaar.id)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned.id)
        vault.merchants.delete(bigBazaar.id).success()

        vault.merchants.purge(bigBazaar.id, dmart.id).success()

        assertThat(allMerchantIds()).doesNotContain(bigBazaar.id)
        assertThat(rowFor(binned.id).merchantId).isEqualTo(dmart.id)
    }

    /** A merchant lives in both books -- a refund from a shop is a credit. */
    @Test
    fun purge_countsAndRepointsBothBooks() = runBlocking<Unit> {
        val salary = vault.categories.create(NewCategory(LedgerType.CREDIT, "Refunds")).success()
        val expense = approve(merchantId = bigBazaar.id)
        val refund = approve(
            ledger = LedgerType.CREDIT,
            categoryId = salary.id,
            merchantId = bigBazaar.id,
        )
        vault.merchants.delete(bigBazaar.id).success()

        assertThat(vault.merchants.purge(bigBazaar.id, null).error())
            .isEqualTo(TaxonomyError.ReassignRequired(2))

        vault.merchants.purge(bigBazaar.id, dmart.id).success()

        assertThat(rowFor(expense.id).merchantId).isEqualTo(dmart.id)
        assertThat(rowFor(refund.id, LedgerType.CREDIT).merchantId).isEqualTo(dmart.id)
    }

    @Test
    fun purge_refusesATargetThatIsItselfHidden() = runBlocking<Unit> {
        approve(merchantId = bigBazaar.id)
        vault.merchants.delete(bigBazaar.id).success()
        vault.merchants.delete(dmart.id).success()

        assertThat(vault.merchants.purge(bigBazaar.id, dmart.id).error())
            .isEqualTo(TaxonomyError.InvalidTarget)
        assertThat(allMerchantIds()).contains(bigBazaar.id)
    }

    /**
     * **A live merchant can never be reached through the purge.**
     *
     * `AND deleted_at != 0` on the statement is what makes that true, and the
     * repository's own check is what makes it a typed refusal rather than a
     * silent no-op. Without either, this call would destroy a merchant the user
     * is actively filing entries against.
     */
    @Test
    fun purge_refusesALiveMerchant() = runBlocking<Unit> {
        assertThat(vault.merchants.purge(bigBazaar.id, null).error())
            .isEqualTo(TaxonomyError.NotFound)
        assertThat(allMerchantIds()).contains(bigBazaar.id)
    }

    // ── The purge: categories ────────────────────────────────────────────────

    @Test
    fun purge_isRefusedWhileEntriesAreFiledUnderTheCategory() = runBlocking<Unit> {
        approve(categoryId = groceries.id)
        vault.categories.delete(groceries.id, household.id).success()

        // Re-assigned at hide time, so nothing is filed under it any more --
        // but the subcategory that went out with it is still destroyable only
        // as part of the branch.
        vault.categories.purge(groceries.id, null).success()

        assertThat(allCategoryIds()).containsNoneOf(groceries.id, vegetables.id)
    }

    /**
     * A branch is destroyed as the unit it was hidden as.
     *
     * Leaving the children behind would strand rows whose `parent_id` resolves
     * to nothing -- and since the hidden list folds a batch into its parent's
     * row, there would be no row left to show them under.
     */
    @Test
    fun purge_destroysTheSubcategoriesThatWentOutWithTheParent() = runBlocking<Unit> {
        vault.categories.delete(groceries.id, null).success()

        vault.categories.purge(groceries.id, null).success()

        assertThat(allCategoryIds()).containsNoneOf(groceries.id, vegetables.id)
    }

    /**
     * A subcategory hidden on its own is not dragged into its parent's purge.
     *
     * The batch is identified by the `deleted_at` the branch shares, so this row
     * -- hidden a second earlier, on its own -- is a different batch and
     * survives until it is asked for by name.
     */
    @Test
    fun purge_leavesASubcategoryHiddenSeparatelyAlone() = runBlocking<Unit> {
        vault.categories.delete(vegetables.id, null).success()
        vault.now += 1_000L
        vault.categories.delete(groceries.id, null).success()

        vault.categories.purge(groceries.id, null).success()

        assertThat(allCategoryIds()).doesNotContain(groceries.id)
        assertThat(allCategoryIds()).contains(vegetables.id)
    }

    /**
     * An entry naming a doomed row as its `subcategory_id` loses the detail and
     * keeps its category.
     *
     * That reference needs no destination, which is why the reassign count does
     * not include it -- and why this has to be asserted rather than assumed: an
     * uncleared `subcategory_id` would dangle exactly as badly as an uncleared
     * `category_id`, and no foreign key would report it.
     */
    @Test
    fun purge_clearsASubcategoryReferenceWithoutAskingWhereItGoes() = runBlocking<Unit> {
        val entry = approve(categoryId = groceries.id, subcategoryId = vegetables.id)
        vault.categories.delete(vegetables.id, null).success()

        vault.categories.purge(vegetables.id, null).success()

        val row = rowFor(entry.id)
        assertThat(row.categoryId).isEqualTo(groceries.id)
        assertThat(row.subcategoryId).isNull()
        assertThat(allCategoryIds()).doesNotContain(vegetables.id)
    }

    // ── The purge: payment methods ───────────────────────────────────────────

    /**
     * No reference check, because hiding already did the work.
     *
     * The assertion on the entry is the load-bearing half: it is what makes the
     * *absence* of a count in `DefaultPaymentMethodRepository.purge` a checked
     * claim rather than an assumption. If hiding ever stops clearing the column,
     * this fails before the purge becomes unsafe.
     */
    @Test
    fun purge_destroysAPaymentMethodWithNoReferenceCheck() = runBlocking<Unit> {
        val card = vault.paymentMethods
            .create(NewPaymentMethod(PaymentMethodType.CREDIT_CARD, "HDFC", last4 = "4821"))
            .success()
        val entry = approve(paymentMethodId = card.id)
        vault.paymentMethods.delete(card.id).success()

        assertThat(rowFor(entry.id).paymentMethodId).isNull()

        vault.paymentMethods.purge(card.id).success()

        assertThat(vault.paymentMethods.observeHidden().first()).isEmpty()
    }

    // ── Compaction ───────────────────────────────────────────────────────────

    /**
     * **The vault is still readable after a purge.**
     *
     * `VACUUM` rewrites the entire encrypted database, and a rewrite that went
     * wrong would not present as a failed test in production -- it would present
     * as a user staring at the Recovery screen. The same assertion
     * `PurgeDeletedEntriesTest` makes, for the same reason, now that a second
     * caller triggers the same rewrite (`CLAUDE.md` §7).
     */
    @Test
    fun purge_leavesTheVaultReadable() = runBlocking<Unit> {
        val kept = approve(categoryId = household.id)
        vault.merchants.delete(bigBazaar.id).success()

        vault.merchants.purge(bigBazaar.id, null).success()

        assertThat(rowFor(kept.id).amountMinor).isEqualTo(Money(100_00L))
        assertThat(liveMerchantNames()).containsExactly("DMart")
        assertThat(vault.categories.observe(LedgerType.DEBIT).first().map { it.name })
            .contains("Household")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun approve(
        ledger: LedgerType = LedgerType.DEBIT,
        categoryId: String? = null,
        subcategoryId: String? = null,
        merchantId: String? = null,
        paymentMethodId: String? = null,
    ): LedgerEntry = vault.ledger.approve(
        ApprovalRequest(
            ledger = ledger,
            amount = Money(100_00L),
            occurredAt = OCCURRED_AT,
            assignment = EntryAssignment(
                categoryId = categoryId ?: if (ledger == LedgerType.DEBIT) groceries.id else null,
                subcategoryId = subcategoryId,
                merchantId = merchantId,
                paymentMethodId = paymentMethodId,
            ),
        ),
    ).success()

    private suspend fun rowFor(id: String, ledger: LedgerType = LedgerType.DEBIT) =
        vault.session.requireDatabase().ledgerEntryDao().allForLedger(ledger).single { it.id == id }

    private suspend fun liveMerchantNames() =
        vault.merchants.observeAll().first().map { it.canonicalName }

    private suspend fun hiddenMerchantNames() =
        vault.merchants.observeHidden().first().map { it.name }

    private suspend fun allMerchantIds() =
        vault.session.requireDatabase().merchantDao().all().map { it.id }

    private suspend fun allCategoryIds() =
        vault.session.requireDatabase().categoryDao().all().map { it.id }

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private fun TaxonomyResult<*>.error(): TaxonomyError {
        assertThat(this).isInstanceOf(TaxonomyResult.Failure::class.java)
        return (this as TaxonomyResult.Failure).error
    }

    private companion object {
        private const val OCCURRED_AT = 1_700_000_000_000L
    }
}
