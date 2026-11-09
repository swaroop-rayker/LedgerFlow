package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.AgeRemovalRecord
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removal by age's data side against a real vault (ADR-0035 §6): which
 * receipts it may consider, each book asked on its own (Law 2), and the setting
 * and record kept in `app_meta`.
 */
@RunWith(AndroidJUnit4::class)
class RemovalByAgeInstrumentedTest {

    private val rig = ReceiptsTestRig("removal-by-age")
    private val setting get() = VaultReceiptAgeRemoval(rig.vault.session, Dispatchers.IO)

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    /** Captured at the vault clock's now, then the clock moves past it. */
    private suspend fun filed(ledger: LedgerType): String =
        rig.storeImage().also { rig.link(it, rig.approvedEntry(ledger, 1_000)) }

    @Test
    fun onlyReceiptsFiledInABook_capturedBefore_andStillOnThePhone_areConsidered() = runTest {
        val expense = filed(LedgerType.DEBIT)
        val income = filed(LedgerType.CREDIT)
        val unfiled = rig.storeImage()
        val binned = filed(LedgerType.DEBIT)
        val binnedEntry = requireNotNull(rig.vault.database.attachmentDao().byId(binned)?.entryId)
        rig.vault.ledger.softDeleteEntry(LedgerType.DEBIT, binnedEntry)
        val removed = filed(LedgerType.DEBIT).also { rig.receipts.removeFromPhone(it) }
        val cutoff = rig.vault.now + 1

        val candidates = rig.receipts.filedBefore(cutoff)

        assertThat(candidates).containsExactly(expense, income)
        assertThat(candidates).containsNoneOf(unfiled, binned, removed)
        // Nothing is older than a cutoff before they were captured.
        assertThat(rig.receipts.filedBefore(rig.vault.now - 1)).isEmpty()
    }

    @Test
    fun theSetting_keepsOnlyTheOwnersChoices_andOffIsOff() = runTest {
        assertThat(setting.afterDays()).isNull()

        setting.setAfterDays(90)
        assertThat(setting.afterDays()).isEqualTo(90)
        assertThat(setting.observeAfterDays().first()).isEqualTo(90)

        setting.setAfterDays(45)
        assertThat(setting.afterDays()).isNull()

        setting.setAfterDays(null)
        assertThat(setting.afterDays()).isNull()
    }

    @Test
    fun theRecord_survivesItsStoredForm() = runTest {
        val record = AgeRemovalRecord(at = 1_794_200_000_000L, removed = 3, bytes = 1_200_000)

        setting.record(record)

        assertThat(setting.observeLast().first()).isEqualTo(record)
    }
}
