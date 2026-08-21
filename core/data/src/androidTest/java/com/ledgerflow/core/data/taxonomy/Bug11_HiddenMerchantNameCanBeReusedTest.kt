package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG11 — hiding a merchant made its name permanently unusable, and using it
 * again threw.
 *
 * Two statements that disagree about what `deleted_at` means:
 *
 * - `index_merchant_normalized_key` is `UNIQUE (normalized_key)`. The column
 *   list does **not** include `deleted_at`, so a hidden row still occupies its
 *   key as far as the constraint is concerned.
 * - `byNormalizedKey` binds `AND deleted_at = 0`, so `createOrGet` could not
 *   see the row that was blocking it.
 *
 * The consequence was not a wrong answer but a throw. `createOrGet` fell through
 * to `dao.insert`, `OnConflictStrategy.ABORT` raised
 * `SQLiteConstraintException`, and it left a repository whose entire contract is
 * typed refusals as an uncaught exception — out of a coroutine, from a screen
 * two taps away: Merchants → Hide "Amazon" → Add merchant → "Amazon".
 *
 * The fix is not a wider index. Two live rows on one key would break merchant
 * matching, which is the thing the key exists for. It is that `createOrGet`
 * looks for the hidden row and restores it — which is also what the person
 * typing the name meant, and it brings the merchant's aliases and default
 * category back with it.
 *
 * **There were two doors, and fixing one left the other open.** `rename` read
 * `byNormalizedKey` for its clash check as well, so renaming an existing
 * merchant onto a hidden one's name threw in exactly the same way. It cannot
 * take `createOrGet`'s fix — the row being renamed already exists, so un-hiding
 * would leave two rows on one key — so it refuses with
 * `NameHeldByHiddenRow`, which names the hidden row instead of reporting a
 * duplicate the user cannot see. That door was found by an instrumented test
 * trying to set up an unrelated scenario, which is the argument for testing this
 * layer against a real database rather than a fake.
 *
 * Instrumented because the bug **is** the constraint. Any fake would have let
 * the insert through and reported the pass this test exists to deny.
 */
@RunWith(AndroidJUnit4::class)
class Bug11_HiddenMerchantNameCanBeReusedTest {

    private val vault = LedgerTestVault("lf_bug11_test")

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    @Test
    fun addingAHiddenMerchantsNameAgain_restoresItInsteadOfThrowing() = runBlocking<Unit> {
        val original = vault.merchants.createOrGet("Amazon").success()
        vault.merchants.delete(original.id).success()

        // The line that used to raise SQLiteConstraintException.
        val reused = vault.merchants.createOrGet("Amazon").success()

        assertThat(reused.id).isEqualTo(original.id)
        assertThat(vault.merchants.observeAll().first().map { it.canonicalName })
            .containsExactly("Amazon")
        assertThat(vault.merchants.observeHidden().first()).isEmpty()
    }

    /**
     * The same collision through the normaliser rather than through an exact
     * match.
     *
     * "SWIGGY*ORDER4821" and "Swiggy" share a `normalized_key`, which is the
     * whole point of the key — so the hidden row blocks the second name just as
     * hard, while looking nothing like it. This is the shape the bug would have
     * taken through ingest at P2, where the raw merchant string is whatever the
     * bank sent.
     */
    @Test
    fun aNameThatNormalisesOntoAHiddenMerchant_restoresItToo() = runBlocking<Unit> {
        val original = vault.merchants.createOrGet("SWIGGY*ORDER4821").success()
        vault.merchants.delete(original.id).success()

        val reused = vault.merchants.createOrGet("Swiggy").success()

        assertThat(reused.id).isEqualTo(original.id)
        // The row keeps the name it was created with. Restoring is not renaming:
        // the user asked for this merchant back, not for it to be relabelled by
        // whatever string happened to match it.
        assertThat(reused.canonicalName).isEqualTo("SWIGGY*ORDER4821")
    }

    /**
     * A merchant that was *purged* leaves no row and no key, so the name is
     * genuinely free again.
     *
     * The counterpart assertion: without it, "restores it instead of throwing"
     * could be satisfied by a `createOrGet` that had quietly stopped being able
     * to create anything at all.
     */
    @Test
    fun aPurgedMerchantsName_createsAFreshRow() = runBlocking<Unit> {
        val original = vault.merchants.createOrGet("Amazon").success()
        vault.merchants.delete(original.id).success()
        vault.merchants.purge(original.id, null).success()

        val fresh = vault.merchants.createOrGet("Amazon").success()

        assertThat(fresh.id).isNotEqualTo(original.id)
        assertThat(vault.merchants.observeAll().first().map { it.canonicalName })
            .containsExactly("Amazon")
    }

    @Test
    fun renamingOntoAHiddenMerchantsName_isRefusedRatherThanThrowing() = runBlocking<Unit> {
        val hidden = vault.merchants.createOrGet("Amazon").success()
        val other = vault.merchants.createOrGet("Flipkart").success()
        vault.merchants.delete(hidden.id).success()

        // The line that used to raise SQLiteConstraintException.
        val outcome = vault.merchants.rename(other.id, "Amazon")

        assertThat(outcome).isInstanceOf(TaxonomyResult.Failure::class.java)
        assertThat((outcome as TaxonomyResult.Failure).error)
            .isEqualTo(TaxonomyError.NameHeldByHiddenRow("Amazon"))
        assertThat(vault.merchants.observeAll().first().map { it.canonicalName })
            .containsExactly("Flipkart")
    }

    /** Renaming a merchant to its own name is not a clash with itself. */
    @Test
    fun renamingAMerchantToItsOwnName_stillSucceeds() = runBlocking<Unit> {
        val merchant = vault.merchants.createOrGet("Amazon").success()

        vault.merchants.rename(merchant.id, "AMAZON").success()

        assertThat(vault.merchants.observeAll().first().map { it.canonicalName })
            .containsExactly("AMAZON")
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
