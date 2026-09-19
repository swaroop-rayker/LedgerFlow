package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 7b (owner, 2026-09-19): a payee name the user corrected is remembered
 * as an alias of the merchant they chose, on a real vault. Exact normalised
 * matches only; a live merchant's own name is never taken from it.
 */
@RunWith(AndroidJUnit4::class)
class MerchantAliasTest {

    private val vault = LedgerTestVault("merchant-alias-test")

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    private fun <T> TaxonomyResult<T>.success(): T = (this as TaxonomyResult.Success).value

    private suspend fun merchant(name: String) = vault.merchants.createOrGet(name).success()

    @Test
    fun aTaughtName_resolvesToItsMerchant_whicheverWayItIsSpelled() = runBlocking<Unit> {
        val zepto = merchant("Zepto")
        vault.merchants.rememberAlias(zepto.id, "Geddit Convenience Private Limited").success()

        assertThat(vault.merchants.findByName("GEDDIT CONVENIENCE PVT LTD")?.id).isEqualTo(zepto.id)
        assertThat(vault.merchants.createOrGet("geddit convenience").success().id).isEqualTo(zepto.id)
        assertThat(vault.database.merchantDao().byNormalizedKey("geddit convenience")).isNull()
    }

    /** A live merchant keeps its own name: teaching it elsewhere stores nothing. */
    @Test
    fun aLiveMerchantsOwnName_isNeverTakenFromIt() = runBlocking<Unit> {
        val amazon = merchant("Amazon")
        val zepto = merchant("Zepto")

        vault.merchants.rememberAlias(zepto.id, "AMAZON").success()

        assertThat(vault.merchants.findByName("Amazon")?.id).isEqualTo(amazon.id)
        assertThat(vault.database.merchantAliasDao().all()).isEmpty()
    }

    /** Taught again under another merchant, the same name moves — one alias, not two. */
    @Test
    fun reTeaching_movesTheAlias() = runBlocking<Unit> {
        val zepto = merchant("Zepto")
        val blinkit = merchant("Blinkit")
        vault.merchants.rememberAlias(zepto.id, "Quick Store Pvt Ltd").success()

        vault.merchants.rememberAlias(blinkit.id, "QUICK STORE PRIVATE LIMITED").success()

        assertThat(vault.merchants.findByName("Quick Store")?.id).isEqualTo(blinkit.id)
        assertThat(vault.database.merchantAliasDao().all()).hasSize(1)
    }

    /** An alias to a hidden merchant resolves to nothing rather than to the hidden row. */
    @Test
    fun anAliasOfAHiddenMerchant_resolvesToNothing() = runBlocking<Unit> {
        val zepto = merchant("Zepto")
        vault.merchants.rememberAlias(zepto.id, "Geddit Convenience").success()
        vault.merchants.delete(zepto.id).success()

        assertThat(vault.merchants.findByName("Geddit Convenience")).isNull()
    }

    /** A merge carries the folded merchant's taught names to the survivor. */
    @Test
    fun aMerge_carriesTheFoldedMerchantsAliases() = runBlocking<Unit> {
        val geddit = merchant("Geddit Convenience")
        val zepto = merchant("Zepto")
        vault.merchants.rememberAlias(geddit.id, "GCPL Hubli").success()

        vault.merchants.merge(sourceId = geddit.id, targetId = zepto.id).success()

        assertThat(vault.merchants.findByName("GCPL Hubli")?.id).isEqualTo(zepto.id)
    }
}
