package com.ledgerflow.core.data.ingest

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.backup.BackupFolder
import com.ledgerflow.core.data.backup.BackupFolderResolver
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.ReceiptDao
import com.ledgerflow.core.database.dao.ReceiptListRow
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptCount
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptImage
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.domain.receipts.ReceiptsRepository
import com.ledgerflow.core.model.LedgerType
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The Receipts page's data (V2-5, ADR-0035).
 *
 * ## Delete reaches the backup folder (BUG88)
 *
 * Until V2-5, deleting receipts removed the rows and the phone's files and left
 * every sealed copy in the backup folder, and Drive kept its copies because the
 * folder still had them. Nothing could use them: a restore looks only for the
 * images its rows name, and the rows were gone. So delete now removes the
 * folder's copy too, when the folder can be reached, and Drive follows on its
 * next pass (ADR-0034 §5 removes what the folder no longer has).
 *
 * **Rows first, then the phone's files, then the folder's copies**, as
 * `DefaultAttachmentRepository.deleteAll` always ordered it: the user asked for
 * the images to be gone, so a row must never outlive its file. Interrupted, the
 * worst left behind is a file with no row, which nothing shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
public class DefaultReceiptsRepository @Inject constructor(
    private val session: VaultSession,
    private val files: AttachmentFiles,
    private val attachments: AttachmentRepository,
    private val folders: BackupFolderResolver,
    private val drive: DriveStatusStore,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ReceiptsRepository {

    override fun observeReceipts(period: ReceiptPeriod): Flow<PagingData<ReceiptListItem>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.receiptDao() ?: return@flatMapLatest flowOf(PagingData.empty())
            val since = since(period)
            // A fresh PagingSource per invalidation (BUG10's reason): Room
            // invalidates on an `attachment` write and the Pager asks again.
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = { dao.pagingReceipts(since) },
            ).flow.map { page -> page.map { row -> row.toItem(dao) } }
        }

    override fun observeCount(period: ReceiptPeriod): Flow<ReceiptCount> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.receiptDao() ?: return@flatMapLatest flowOf(ReceiptCount.None)
            dao.observeCount(since(period)).map { ReceiptCount(it.count, it.bytes) }
        }

    override suspend fun idsIn(period: ReceiptPeriod): List<String> = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext emptyList()
        database.receiptDao().idsSince(since(period))
    }

    override suspend fun receipt(id: String): ReceiptListItem? = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext null
        val dao = database.receiptDao()
        dao.receipt(id)?.toItem(dao)
    }

    /**
     * The file wins when it is there, even with the column set: that is a
     * removal that did not finish unlinking, and the image is still readable.
     */
    override suspend fun image(id: String): ReceiptImage {
        attachments.read(id)?.let { return ReceiptImage.Available(it) }
        val removedAt = withContext(io) {
            session.openForBackgroundWork()?.receiptDao()?.receipt(id)?.removedFromPhoneAt
        }
        return removedAt?.let(ReceiptImage::RemovedFromPhone) ?: ReceiptImage.Unavailable
    }

    override fun observeRemoved(): Flow<ReceiptCount> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.receiptDao() ?: return@flatMapLatest flowOf(ReceiptCount.None)
            dao.observeRemoved().map { ReceiptCount(it.count, it.bytes) }
        }

    /**
     * The column first, then the file (ADR-0035 §3). A process death between
     * the two leaves a marked row whose file is still there, which [image]
     * still shows; nothing is lost.
     * The DAO refuses an Inbox candidate's image and one already marked.
     */
    override suspend fun removeFromPhone(id: String): Boolean = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext false
        val dao = database.receiptDao()
        val row = dao.byIds(listOf(id)).singleOrNull() ?: return@withContext false
        val marked = runCatching { dao.markRemovedFromPhone(id, clock.nowMillis()) }.getOrDefault(0) > 0
        if (marked) files.delete(listOf(row.filePath))
        marked
    }

    override suspend fun copies(): ReceiptCopies = withContext(io) {
        val database = session.openForBackgroundWork()
        ReceiptCopies(
            backupFolder = database?.let { reachableFolder(it) }?.displayName(),
            drive = runCatching { drive.enabled() }.getOrDefault(false),
        )
    }

    override suspend fun delete(ids: Set<String>): ReceiptDeletion = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext ReceiptDeletion.Nothing
        deleteRows(database, ids.toList())
    }

    override suspend fun deleteAll(): ReceiptDeletion = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext ReceiptDeletion.Nothing
        val deletion = deleteRows(database, database.attachmentDao().all().map { it.id })
        // Files with no row, left by an interrupted earlier delete: everything
        // means everything, and nothing else enumerates that directory.
        files.all().forEach { it.delete() }
        deletion
    }

    private suspend fun deleteRows(database: LedgerFlowDatabase, ids: List<String>): ReceiptDeletion {
        if (ids.isEmpty()) return ReceiptDeletion.Nothing
        val dao = database.receiptDao()
        val rows = ids.chunked(SQL_VARIABLE_CHUNK).flatMap { dao.byIds(it) }
        if (rows.isEmpty()) return ReceiptDeletion.Nothing

        val deleted = runCatching {
            rows.map { it.id }.chunked(SQL_VARIABLE_CHUNK).sumOf { dao.deleteByIds(it) }
        }.getOrDefault(0)
        if (deleted == 0) return ReceiptDeletion.Nothing
        files.delete(rows.map { it.filePath })

        val images = reachableFolder(database)?.subfolder(AttachmentBackup.IMAGES_DIRECTORY)
        val removed = images?.let { folder ->
            runCatching { folder.deleteEach(rows.map { "${it.id}.${AttachmentBackup.EXTENSION}" }) }.getOrDefault(0)
        } ?: 0
        return ReceiptDeletion(
            deleted = deleted,
            bytes = rows.sumOf { it.bytes },
            backupCopiesRemoved = removed,
            backupFolderUnreachable = images == null,
        )
    }

    /** The same test the backup passes use: the grant is held and the folder still exists. */
    private suspend fun reachableFolder(database: LedgerFlowDatabase): BackupFolder? =
        runCatching {
            database.appMetaDao().value(VaultSession.KEY_BACKUP_TREE_URI)
                ?.let(folders::resolve)
                ?.takeIf { it.displayName() != null }
        }.getOrNull()

    private fun since(period: ReceiptPeriod): Long = period.since(clock.nowMillis(), ZoneId.systemDefault())

    /**
     * What the receipt belongs to. Each book is asked separately (Law 2): a
     * statement naming both views is what `LedgerIsolationTest` forbids.
     */
    private suspend fun ReceiptListRow.toItem(dao: ReceiptDao): ReceiptListItem {
        val entry = entryId
        val pending = pendingId
        val filing = when {
            entry != null -> filedIn(dao, entry) ?: ReceiptFiling.InBin
            pending != null -> ReceiptFiling.InInbox(pending)
            else -> ReceiptFiling.NotFiled
        }
        return ReceiptListItem(
            id = id,
            capturedAt = createdAt,
            bytes = bytes,
            filing = filing,
            removedFromPhoneAt = removedFromPhoneAt,
        )
    }

    private suspend fun filedIn(dao: ReceiptDao, entryId: String): ReceiptFiling.Filed? {
        dao.debitEntry(entryId)?.let { return it.filed(LedgerType.DEBIT) }
        return dao.creditEntry(entryId)?.filed(LedgerType.CREDIT)
    }

    private fun com.ledgerflow.core.database.dao.ReceiptEntryRow.filed(ledger: LedgerType) = ReceiptFiling.Filed(
        ledger = ledger,
        merchantName = merchantName,
        categoryName = categoryName,
        amount = amountMinor,
        currency = currency,
        localDate = localDate,
    )

    private companion object {
        /** The Ledger's page size. */
        const val PAGE_SIZE = 30

        /** Under SQLite's default limit of 999 bound variables per statement. */
        const val SQL_VARIABLE_CHUNK = 500
    }
}
