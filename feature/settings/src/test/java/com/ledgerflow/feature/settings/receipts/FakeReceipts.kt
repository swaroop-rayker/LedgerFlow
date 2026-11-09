package com.ledgerflow.feature.settings.receipts

import androidx.paging.PagingData
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptCount
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptImage
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.domain.receipts.ReceiptsRepository
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.receipts.AgeRemovalRecord
import com.ledgerflow.core.domain.receipts.ReceiptAgeRemoval
import com.ledgerflow.core.domain.receipts.ReceiptBackupCopies
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.RemoveReceiptsFromPhoneUseCase
import com.ledgerflow.core.testing.drive.FakeDriveAccess
import com.ledgerflow.core.testing.drive.FakeDriveFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** An in-memory store of receipts, for the page's ViewModels. */
internal class FakeReceipts(
    initial: List<ReceiptListItem> = previewReceipts,
) : ReceiptsRepository, AttachmentRepository, ReceiptAgeRemoval {

    val rows = MutableStateFlow(initial)
    val deletedIds = mutableListOf<Set<String>>()
    var deletedAll = 0
    var image: ReceiptImage = ReceiptImage.Unavailable
    var copies = ReceiptCopies(backupFolder = "LedgerFlow", drive = true)

    /** How many times the list was asked for: a new period is a new list. */
    var listRequests = 0

    override fun observeReceipts(period: ReceiptPeriod): Flow<PagingData<ReceiptListItem>> {
        listRequests++
        return rows.map { PagingData.from(it) }
    }

    override fun observeCount(period: ReceiptPeriod): Flow<ReceiptCount> =
        rows.map { list -> ReceiptCount(list.size, list.sumOf { it.bytes }) }

    override suspend fun idsIn(period: ReceiptPeriod): List<String> = rows.value.map { it.id }

    override suspend fun receipt(id: String): ReceiptListItem? = rows.value.firstOrNull { it.id == id }

    override suspend fun image(id: String): ReceiptImage = image

    override suspend fun copies(): ReceiptCopies = copies

    override suspend fun delete(ids: Set<String>): ReceiptDeletion {
        deletedIds += ids
        val doomed = rows.value.filter { it.id in ids }
        rows.value = rows.value - doomed.toSet()
        return ReceiptDeletion(doomed.size, doomed.sumOf { it.bytes }, doomed.size, backupFolderUnreachable = false)
    }

    override suspend fun deleteAll(): ReceiptDeletion {
        deletedAll++
        return delete(rows.value.map { it.id }.toSet())
    }

    override suspend fun store(bytes: ByteArray, mime: String): AttachmentOutcome = AttachmentOutcome.WriteFailed

    override suspend fun read(attachmentId: String): ByteArray? = null

    override suspend fun usage(): AttachmentUsage = rows.value.filter { it.removedFromPhoneAt == null }
        .let { onPhone -> AttachmentUsage(count = onPhone.size, bytes = onPhone.sumOf { it.bytes }) }

    override fun observeRemoved(): Flow<ReceiptCount> = rows.map { list ->
        list.filter { it.removedFromPhoneAt != null }.let { ReceiptCount(it.size, it.sumOf { row -> row.bytes }) }
    }

    override suspend fun removeFromPhone(id: String): Boolean {
        val row = rows.value.firstOrNull { it.id == id } ?: return false
        if (row.removedFromPhoneAt != null || row.filing is ReceiptFiling.InInbox) return false
        rows.value = rows.value.map { if (it.id == id) it.copy(removedFromPhoneAt = REMOVED_AT) else it }
        return true
    }

    override suspend fun filedBefore(before: Long): List<String> = rows.value
        .filter { it.capturedAt < before && it.removedFromPhoneAt == null && it.filing is ReceiptFiling.Filed }
        .map { it.id }

    // ── Removal by age's setting (ADR-0035 §6), in memory ──────────────────

    val afterDays = MutableStateFlow<Int?>(null)
    val lastRun = MutableStateFlow<AgeRemovalRecord?>(null)

    override fun observeAfterDays(): Flow<Int?> = afterDays
    override suspend fun afterDays(): Int? = afterDays.value
    override suspend fun setAfterDays(days: Int?) {
        afterDays.value = days
    }
    override fun observeLast(): Flow<AgeRemovalRecord?> = lastRun
    override suspend fun record(record: AgeRemovalRecord) {
        lastRun.value = record
    }

    // ── What "Remove from phone" checks (ADR-0035 §1), in memory ────────────

    var driveOn = true
    val drive = FakeDriveFiles()
    private val sealed = mutableMapOf<String, ByteArray>()

    /** As after a nightly pass and a Drive copy: the folder's sealed copy, and the same on Drive. */
    fun backedUp(id: String) {
        val bytes = id.toByteArray()
        sealed[id] = bytes
        drive.receipt("$id.lfba", bytes)
    }

    private val status = object : DriveStatusStore {
        override fun observe(): Flow<DriveStatus> =
            flowOf(DriveStatus(enabled = driveOn, lastAttempt = null, lastCopiedAt = null))
        override suspend fun enabled() = driveOn
        override suspend fun account(): String? = null
        override suspend fun setEnabled(enabled: Boolean, account: String?) = Unit
        override suspend fun record(attempt: DriveAttempt) = Unit
    }

    private val copiesInFolder = object : ReceiptBackupCopies {
        override suspend fun sealedCopy(id: String) = sealed[id]
    }

    /** The real use case over these fakes. */
    fun removeUseCase(): RemoveReceiptsFromPhoneUseCase =
        RemoveReceiptsFromPhoneUseCase(status, FakeDriveAccess(), drive, copiesInFolder, this)

    companion object {
        const val REMOVED_AT = 1_794_100_000_000L

        fun empty(): Flow<PagingData<ReceiptListItem>> = flowOf(PagingData.empty())
    }
}
