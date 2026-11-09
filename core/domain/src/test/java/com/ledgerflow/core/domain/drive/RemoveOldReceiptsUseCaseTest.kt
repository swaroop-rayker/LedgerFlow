package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.AgeRemovalRecord
import com.ledgerflow.core.domain.receipts.ReceiptAgeRemoval
import com.ledgerflow.core.domain.receipts.ReceiptBackupCopies
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.RemoveOldReceiptsUseCase
import com.ledgerflow.core.domain.receipts.RemoveReceiptsFromPhoneUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Removal by age (ADR-0035 §6): off until chosen; only receipts filed in a book
 * and older than the age; each through the same Drive check as "Remove from
 * phone"; a record only when something was removed.
 */
class RemoveOldReceiptsUseCaseTest {

    private val setting = object : ReceiptAgeRemoval {
        val days = MutableStateFlow<Int?>(90)
        val last = MutableStateFlow<AgeRemovalRecord?>(null)
        override fun observeAfterDays(): Flow<Int?> = days
        override suspend fun afterDays() = days.value
        override suspend fun setAfterDays(days: Int?) {
            this.days.value = days
        }
        override fun observeLast(): Flow<AgeRemovalRecord?> = last
        override suspend fun record(record: AgeRemovalRecord) {
            last.value = record
        }
    }

    private val drive = FakeDrive()
    private val sealed = mutableMapOf<String, ByteArray>()
    private val copies = object : ReceiptBackupCopies {
        override suspend fun sealedCopy(id: String) = sealed[id]
    }
    private val filed = ReceiptFiling.Filed(LedgerType.DEBIT, "Big Bazaar", null, Money(1_240_00), "INR", 0)
    private val receipts = FakeReceipts(
        ReceiptListItem("old", capturedAt = NOW - 100 * DAY, bytes = 412_000, filing = filed),
        ReceiptListItem("young", capturedAt = NOW - 10 * DAY, bytes = 268_000, filing = filed),
        ReceiptListItem("old-unfiled", capturedAt = NOW - 100 * DAY, bytes = 1, filing = ReceiptFiling.NotFiled),
    )
    private val remove = RemoveReceiptsFromPhoneUseCase(
        FakeStatus(enabled = true),
        FakeAccess(),
        drive,
        copies,
        receipts,
    )
    private val removeOld = RemoveOldReceiptsUseCase(setting, receipts, remove) { NOW }

    private fun backedUp(vararg ids: String) = ids.forEach { id ->
        sealed[id] = id.toByteArray()
        drive.put(drive.receipts(), "$id.lfba", id.toByteArray())
    }

    @Test
    fun anOldFiledReceipt_withACheckedCopy_leaves_andTheRunIsRecorded() = runBlocking<Unit> {
        backedUp("old", "young", "old-unfiled")

        val record = removeOld()

        assertThat(receipts.removed).containsExactly("old")
        assertThat(record).isEqualTo(AgeRemovalRecord(at = NOW, removed = 1, bytes = 412_000))
        assertThat(setting.last.value).isEqualTo(record)
    }

    /** Younger than the age, or never filed: not considered at all. */
    @Test
    fun youngOrUnfiledReceipts_stay() = runBlocking<Unit> {
        backedUp("young", "old-unfiled")

        removeOld()

        assertThat(receipts.removed).isEmpty()
    }

    @Test
    fun off_removesNothing_andRecordsNothing() = runBlocking<Unit> {
        backedUp("old")
        setting.days.value = null

        assertThat(removeOld()).isNull()
        assertThat(receipts.removed).isEmpty()
        assertThat(setting.last.value).isNull()
    }

    /** Old enough, but no checked copy: it stays, and a run that removed nothing leaves no record. */
    @Test
    fun anOldReceiptWithoutACheckedCopy_stays_andNothingIsRecorded() = runBlocking<Unit> {
        assertThat(removeOld()).isNull()
        assertThat(receipts.removed).isEmpty()
        assertThat(setting.last.value).isNull()
    }

    @Test
    fun aShorterAge_takesYoungerReceipts() = runBlocking<Unit> {
        backedUp("old", "young")
        setting.days.value = 7

        removeOld()

        assertThat(receipts.removed).containsExactly("old", "young")
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_794_200_000_000L
    }
}
