package com.ledgerflow.core.domain.receipts

import androidx.paging.PagingData
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * The Receipts page (V2-5, V2-PLAN §3.7, ADR-0035): every stored receipt image,
 * by when it was captured, with what it belongs to.
 *
 * Separate from `AttachmentRepository`, which stores and reads one image for
 * the capture path. This one browses and deletes, and it is the only place
 * that reaches the backup folder's copy of an image to delete it (BUG88).
 */
public interface ReceiptsRepository {

    /** Newest first. Paged: a phone keeps receipts for years. */
    public fun observeReceipts(period: ReceiptPeriod): Flow<PagingData<ReceiptListItem>>

    /** How many receipts [period] holds and their size, for the line above the list. */
    public fun observeCount(period: ReceiptPeriod): Flow<ReceiptCount>

    /** Every receipt id in [period]: "Select all" takes what the page shows. */
    public suspend fun idsIn(period: ReceiptPeriod): List<String>

    /** One receipt, for its own page. Null when it no longer exists. */
    public suspend fun receipt(id: String): ReceiptListItem?

    /** The image itself, decrypted into memory only (Law 5: never `cacheDir`). */
    public suspend fun image(id: String): ReceiptImage

    /** Where the copies of the receipts are, for the page's summary. */
    public suspend fun copies(): ReceiptCopies

    /**
     * Deletes [ids]: each row, its file on the phone and, when the backup folder
     * can be reached, its copy there (BUG88). Google Drive's copy goes on Drive's
     * next pass, which removes what the folder no longer has (ADR-0034 §5).
     * Irreversible. The entries the receipts belonged to are untouched.
     */
    public suspend fun delete(ids: Set<String>): ReceiptDeletion

    /** [delete] for every receipt there is. */
    public suspend fun deleteAll(): ReceiptDeletion
}

/**
 * The page's four windows. **Rolling, not calendar**, like the Ledger's bands
 * (`RecencyBucket`): Week is today and the six days before it, Month today and
 * the 29 before. Measured from when the receipt was captured.
 */
public enum class ReceiptPeriod(private val daysBack: Long?) {
    Today(0),
    Week(6),
    Month(29),
    All(null),
    ;

    /** The first instant this period includes, in epoch millis; 0 for [All]. */
    public fun since(nowMillis: Long, zone: ZoneId): Long {
        val days = daysBack ?: return 0L
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return startOf(today.minusDays(days), zone)
    }

    private fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** One receipt as the list and its page show it. */
public data class ReceiptListItem(
    val id: String,
    /** When it was captured: `attachment.created_at`. */
    val capturedAt: Long,
    /** The image's size. */
    val bytes: Long,
    val filing: ReceiptFiling,
)

/** What a receipt belongs to. */
public sealed interface ReceiptFiling {

    /** Approved into an entry that is in its book. */
    public data class Filed(
        val ledger: LedgerType,
        val merchantName: String?,
        val categoryName: String?,
        val amount: Money,
        val currency: String,
        /** The entry's date, as days since the epoch. */
        val localDate: Int,
    ) : ReceiptFiling

    /** Its entry is in the bin. */
    public data object InBin : ReceiptFiling

    /** Its candidate is waiting in the Inbox. */
    public data class InInbox(val pendingId: String) : ReceiptFiling

    /** Scanned and never approved: the candidate was discarded or suppressed. */
    public data object NotFiled : ReceiptFiling
}

public data class ReceiptCount(val count: Int, val bytes: Long) {
    public companion object {
        public val None: ReceiptCount = ReceiptCount(0, 0L)
    }
}

/** Where copies of the receipts are kept, besides this phone. */
public data class ReceiptCopies(
    /** The backup folder's name, or null when there is none (or its grant is gone). */
    val backupFolder: String?,
    /** Google Drive backup is on (ADR-0034). */
    val drive: Boolean,
)

public sealed interface ReceiptImage {
    /** The decrypted image bytes, as stored (PNG, ≤1600 px on the long edge). */
    public class Available(public val bytes: ByteArray) : ReceiptImage

    /** The file is missing or does not open: honest degradation (ADR-0023). */
    public data object Unavailable : ReceiptImage
}

/** What a delete did. */
public data class ReceiptDeletion(
    val deleted: Int,
    val bytes: Long,
    /** Copies removed from the backup folder (BUG88). */
    val backupCopiesRemoved: Int,
    /** The folder could not be reached, so its copies of these receipts stay. */
    val backupFolderUnreachable: Boolean,
) {
    public companion object {
        public val Nothing: ReceiptDeletion = ReceiptDeletion(0, 0L, 0, false)
    }
}
