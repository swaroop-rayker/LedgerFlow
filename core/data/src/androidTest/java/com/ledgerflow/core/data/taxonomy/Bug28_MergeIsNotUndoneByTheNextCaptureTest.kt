package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Merchant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG28: **a merge was undone by the next message naming the merged merchant.**
 *
 * Merging "Geddit Convenience" into "Zepto" hides the Geddit row. The next SMS
 * or receipt naming Geddit reaches `createOrGet`, whose BUG11 path — a name
 * held by a *hidden* merchant un-hides that merchant rather than failing the
 * unique key — restored Geddit, and every later entry filed there again: the
 * user's merge quietly reversed, entry by entry. Found while building item 7b
 * (merchant aliases), which is what fixes it — a merge now records the merged
 * name as an alias of the survivor, and aliases resolve before the un-hide.
 */
@RunWith(AndroidJUnit4::class)
class Bug28_MergeIsNotUndoneByTheNextCaptureTest {

    private val vault = LedgerTestVault("bug28-test")

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    private fun <T> TaxonomyResult<T>.success(): T = (this as TaxonomyResult.Success).value

    @Test
    fun bug28_aMergedMerchantsName_resolvesToTheSurvivor_andStaysHidden() = runBlocking<Unit> {
        val geddit = vault.merchants.createOrGet("Geddit Convenience Private Limited").success()
        val zepto = vault.merchants.createOrGet("Zepto").success()
        vault.merchants.merge(sourceId = geddit.id, targetId = zepto.id).success()

        val next: Merchant = vault.merchants.createOrGet("GEDDIT CONVENIENCE PRIVATE LIMITED").success()

        assertThat(next.id).isEqualTo(zepto.id)
        assertThat(vault.merchants.observeAll().first().map { it.id }).doesNotContain(geddit.id)
    }
}
