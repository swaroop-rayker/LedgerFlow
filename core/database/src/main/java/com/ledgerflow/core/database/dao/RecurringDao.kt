package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.ledgerflow.core.database.entity.RecurringDismissalEntity
import com.ledgerflow.core.database.entity.RecurringItemEntity
import com.ledgerflow.core.database.entity.RecurringOccurrenceEntity
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * Recurring items, what each due date turned into, and dismissed suggestions
 * (V2-PLAN §4.2, schema v16).
 *
 * Nothing here writes `ledger_entry` (Law 1). The two match reads go through
 * the book views, one per book (Law 2), and only look.
 */
@Dao
public interface RecurringDao {

    @Query("SELECT * FROM recurring_item ORDER BY ledger, next_due, name")
    public fun observeItems(): Flow<List<RecurringItemEntity>>

    @Query("SELECT * FROM recurring_item WHERE id = :id")
    public suspend fun item(id: String): RecurringItemEntity?

    /** What the daily pass works through. */
    @Query("SELECT * FROM recurring_item WHERE paused = 0 ORDER BY next_due")
    public suspend fun activeItems(): List<RecurringItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertItem(item: RecurringItemEntity)

    @Update
    public suspend fun updateItem(item: RecurringItemEntity): Int

    /** Its occurrences go with it (`ON DELETE CASCADE`); entries are untouched. */
    @Query("DELETE FROM recurring_item WHERE id = :id")
    public suspend fun deleteItem(id: String): Int

    /** A merchant merge: the items that named the folded merchant follow it. */
    @Query("UPDATE recurring_item SET merchant_id = :to, updated_at = :now WHERE merchant_id = :from")
    public suspend fun repointMerchant(from: String, to: String, now: Long): Int

    @Query("SELECT * FROM recurring_occurrence WHERE item_id = :itemId AND due_date = :dueDate")
    public suspend fun occurrence(itemId: String, dueDate: Int): RecurringOccurrenceEntity?

    @Upsert
    public suspend fun upsertOccurrence(occurrence: RecurringOccurrenceEntity)

    /** One item's log, newest first. */
    @Query("SELECT * FROM recurring_occurrence WHERE item_id = :itemId ORDER BY due_date DESC LIMIT :limit")
    public fun observeOccurrences(itemId: String, limit: Int): Flow<List<RecurringOccurrenceEntity>>

    /** Entries and candidates some due date already answered with: each answers one. */
    @Query("SELECT entry_id FROM recurring_occurrence WHERE entry_id IS NOT NULL")
    public suspend fun claimedEntryIds(): List<String>

    @Query("SELECT pending_id FROM recurring_occurrence WHERE pending_id IS NOT NULL")
    public suspend fun claimedPendingIds(): List<String>

    /** Live debit entries on these days, for matching a due date (Law 2: one book). */
    @Query(
        "SELECT id, amount_minor, merchant_id, local_date FROM debit_entries " +
            "WHERE local_date BETWEEN :fromDay AND :toDay ORDER BY local_date, occurred_at",
    )
    public suspend fun debitsBetween(fromDay: Int, toDay: Int): List<RecurringMatchRow>

    /** Live credit entries on these days. See [debitsBetween]. */
    @Query(
        "SELECT id, amount_minor, merchant_id, local_date FROM credit_entries " +
            "WHERE local_date BETWEEN :fromDay AND :toDay ORDER BY local_date, occurred_at",
    )
    public suspend fun creditsBetween(fromDay: Int, toDay: Int): List<RecurringMatchRow>

    @Upsert
    public suspend fun upsertDismissal(dismissal: RecurringDismissalEntity)

    @Query("SELECT * FROM recurring_dismissal")
    public fun observeDismissals(): Flow<List<RecurringDismissalEntity>>

    // Backup (`DatabaseBackupManager`) only.

    @Query("SELECT * FROM recurring_item ORDER BY id")
    public suspend fun allItems(): List<RecurringItemEntity>

    @Query("SELECT * FROM recurring_occurrence ORDER BY id")
    public suspend fun allOccurrences(): List<RecurringOccurrenceEntity>

    @Query("SELECT * FROM recurring_dismissal ORDER BY merchant_id, ledger")
    public suspend fun allDismissals(): List<RecurringDismissalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllItems(items: List<RecurringItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllOccurrences(occurrences: List<RecurringOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllDismissals(dismissals: List<RecurringDismissalEntity>)
}

/** One live entry a due date might be answered by. */
public data class RecurringMatchRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "merchant_id") val merchantId: String?,
    @ColumnInfo(name = "local_date") val localDate: Int,
)
