package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG82: merging two merchants left Analytics' daily summaries naming the
 * folded one until the nightly reconciliation.
 *
 * The merge moved every entry to the surviving merchant but did not re-sum
 * the summaries, which are keyed by merchant too. Analytics kept showing the
 * folded merchant's spending under its old name, and from V2-6 under its old
 * merchant category, for up to a day. Found planning V2-6 (2026-10-31).
 *
 * Red before: the summaries still carry the source merchant after the merge.
 */
@RunWith(AndroidJUnit4::class)
class Bug82_MergeRefreshesTheSummariesTest {

    private val vault = LedgerTestVault("lf_bug82_test")

    @Before
    fun setUp() = runBlocking { vault.open() }

    @After
    fun tearDown() = vault.close()

    private fun <T> TaxonomyResult<T>.value(): T = (this as TaxonomyResult.Success).value

    private suspend fun approve(merchantId: String, amount: Long, day: Int, ledger: LedgerType) {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = ledger,
                amount = Money(amount),
                occurredAt = DAY_MILLIS * day + NOON,
                assignment = EntryAssignment(merchantId = merchantId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    @Test
    fun aMerge_movesTheMerchantsSummariesWithItsEntries_inBothBooks() = runBlocking {
        val survivor = vault.merchants.createOrGet("Big Bazaar").value()
        val folded = vault.merchants.createOrGet("BigBazaar Online").value()
        approve(folded.id, 1_240_50L, day = 20_000, ledger = LedgerType.DEBIT)
        approve(folded.id, 300_00L, day = 20_030, ledger = LedgerType.DEBIT)
        approve(folded.id, 99_00L, day = 20_010, ledger = LedgerType.CREDIT)
        approve(survivor.id, 500_00L, day = 20_015, ledger = LedgerType.DEBIT)

        vault.merchants.merge(sourceId = folded.id, targetId = survivor.id)

        val rollups = vault.session.requireDatabase().dailyRollupDao()
        LedgerType.entries.forEach { ledger ->
            val rows = rollups.allFor(ledger)
            assertThat(rows.map { it.merchantId }.distinct()).containsExactly(survivor.id)
        }
        assertThat(rollups.allFor(LedgerType.DEBIT).sumOf { it.sumMinor.minor }).isEqualTo(2_040_50L)
        assertThat(rollups.allFor(LedgerType.CREDIT).sumOf { it.sumMinor.minor }).isEqualTo(99_00L)
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        const val NOON = 43_200_000L
    }
}
