package com.ledgerflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.ledgerflow.core.database.entity.AttachmentEntity
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * The Receipts page's reads and its delete (V2-5, ADR-0035). No schema change.
 *
 * **The list reads `attachment` alone, and each row's entry is looked up per
 * book** ([debitEntry], [creditEntry]). A single statement naming both
 * `debit_entries` and `credit_entries` is what `LedgerIsolationTest` forbids
 * (Law 2), and a receipt's entry is in exactly one of them. A receipt whose
 * `entry_id` matches neither belongs to an entry in the bin: the views exclude
 * deleted rows, and the base table is not read here at all.
 */
@Dao
public interface ReceiptDao {

    /**
     * Newest first, captured at or after [since] (epoch millis). [ReceiptListRow.pendingId]
     * is the candidate still waiting in the Inbox for this image, if any.
     */
    @Query(
        "SELECT a.id AS id, a.entry_id AS entry_id, a.bytes AS bytes, a.created_at AS created_at, " +
            "a.removed_from_phone_at AS removed_from_phone_at, " +
            "(SELECT p.id FROM pending_transaction p WHERE p.raw_ref_id = a.id " +
            "AND p.status = 'PENDING' LIMIT 1) AS pending_id " +
            "FROM attachment a WHERE a.created_at >= :since " +
            "ORDER BY a.created_at DESC, a.id DESC",
    )
    public fun pagingReceipts(since: Long): PagingSource<Int, ReceiptListRow>

    /** [pagingReceipts]'s row for one receipt. */
    @Query(
        "SELECT a.id AS id, a.entry_id AS entry_id, a.bytes AS bytes, a.created_at AS created_at, " +
            "a.removed_from_phone_at AS removed_from_phone_at, " +
            "(SELECT p.id FROM pending_transaction p WHERE p.raw_ref_id = a.id " +
            "AND p.status = 'PENDING' LIMIT 1) AS pending_id " +
            "FROM attachment a WHERE a.id = :id",
    )
    public suspend fun receipt(id: String): ReceiptListRow?

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(bytes), 0) AS bytes FROM attachment WHERE created_at >= :since")
    public fun observeCount(since: Long): Flow<ReceiptCountRow>

    @Query("SELECT id FROM attachment WHERE created_at >= :since ORDER BY created_at DESC, id DESC")
    public suspend fun idsSince(since: Long): List<String>

    @Query("SELECT * FROM attachment WHERE id IN (:ids)")
    public suspend fun byIds(ids: List<String>): List<AttachmentEntity>

    @Query("DELETE FROM attachment WHERE id IN (:ids)")
    public suspend fun deleteByIds(ids: List<String>): Int

    /** Receipts removed from the phone, kept only in backups (ADR-0035). */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(bytes), 0) AS bytes FROM attachment " +
            "WHERE removed_from_phone_at IS NOT NULL",
    )
    public fun observeRemoved(): Flow<ReceiptCountRow>

    /**
     * Marks [id] removed from the phone, **unless** it already is, or its
     * candidate is still waiting in the Inbox (ADR-0035 §2): the predicate is
     * in the statement, so no caller can skip it.
     *
     * @return 1 when marked, 0 when refused.
     */
    @Query(
        "UPDATE attachment SET removed_from_phone_at = :at " +
            "WHERE id = :id AND removed_from_phone_at IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM pending_transaction p " +
            "WHERE p.raw_ref_id = attachment.id AND p.status = 'PENDING')",
    )
    public suspend fun markRemovedFromPhone(id: String, at: Long): Int

    /** The image is on the phone again (a restore brought it back). */
    @Query("UPDATE attachment SET removed_from_phone_at = NULL WHERE id = :id")
    public suspend fun clearRemovedFromPhone(id: String)

    /**
     * Removal by age's candidates in the expense book (ADR-0035 §2): captured
     * before [before], still on the phone, filed to an entry in the book. One
     * book per statement (Law 2); the bin is excluded by the view itself.
     */
    @Query(
        "SELECT a.id FROM attachment a JOIN debit_entries e ON e.id = a.entry_id " +
            "WHERE a.created_at < :before AND a.removed_from_phone_at IS NULL",
    )
    public suspend fun debitFiledBefore(before: Long): List<String>

    /** The income book's mirror of [debitFiledBefore]. */
    @Query(
        "SELECT a.id FROM attachment a JOIN credit_entries e ON e.id = a.entry_id " +
            "WHERE a.created_at < :before AND a.removed_from_phone_at IS NULL",
    )
    public suspend fun creditFiledBefore(before: Long): List<String>

    /** The expense book's entry a receipt belongs to, if it is one of them. */
    @Query(
        "SELECT e.amount_minor AS amount_minor, e.currency AS currency, e.local_date AS local_date, " +
            "m.canonical_name AS merchant_name, c.name AS category_name " +
            "FROM debit_entries e " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "WHERE e.id = :entryId",
    )
    public suspend fun debitEntry(entryId: String): ReceiptEntryRow?

    /** The income book's mirror of [debitEntry]. */
    @Query(
        "SELECT e.amount_minor AS amount_minor, e.currency AS currency, e.local_date AS local_date, " +
            "m.canonical_name AS merchant_name, c.name AS category_name " +
            "FROM credit_entries e " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "WHERE e.id = :entryId",
    )
    public suspend fun creditEntry(entryId: String): ReceiptEntryRow?
}

public data class ReceiptListRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String?,
    @ColumnInfo(name = "bytes") val bytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "pending_id") val pendingId: String?,
    @ColumnInfo(name = "removed_from_phone_at") val removedFromPhoneAt: Long?,
)

public data class ReceiptCountRow(
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "bytes") val bytes: Long,
)

public data class ReceiptEntryRow(
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "local_date") val localDate: Int,
    @ColumnInfo(name = "merchant_name") val merchantName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
)
