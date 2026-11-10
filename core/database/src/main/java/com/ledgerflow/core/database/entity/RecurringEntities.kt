package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * A recurring payment or receipt the user expects: rent, a subscription, an
 * EMI, a salary (V2-PLAN §4.2, schema v16). Also what a bill reminder is
 * (§4.4): an item with [toInbox] off only reminds.
 *
 * **It never writes the ledger** (Law 1). A due date it cannot match to
 * anything captured becomes, at most, an Inbox candidate the user approves.
 *
 * **One book per item** ([ledger]); nothing sums items across the two.
 *
 * **No foreign keys on [merchantId], [categoryId], [subcategoryId]**, as
 * `budget` has none (ADR-0016): a merchant merge re-points items in code.
 */
@Entity(
    tableName = "recurring_item",
    indices = [
        // The daily pass reads what is due.
        Index(value = ["next_due"]),
    ],
)
public data class RecurringItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "ledger")
    val ledger: LedgerType,

    /** Base currency, positive: the expected amount (Law 3). */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Money,

    /** A bill whose amount changes: matched on merchant and book alone. */
    @ColumnInfo(name = "amount_varies", defaultValue = "0")
    val amountVaries: Boolean = false,

    @ColumnInfo(name = "merchant_id")
    val merchantId: String?,

    @ColumnInfo(name = "category_id")
    val categoryId: String?,

    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String?,

    /** `WEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY` or `EVERY_N_DAYS`. */
    @ColumnInfo(name = "cadence")
    val cadence: String,

    /** Set only for `EVERY_N_DAYS`. */
    @ColumnInfo(name = "every_days")
    val everyDays: Int?,

    /** Days since epoch: the first due day. Monthly repeats keep its day of month. */
    @ColumnInfo(name = "anchor_date")
    val anchorDate: Int,

    /** Days since epoch: the earliest due date not yet resolved. */
    @ColumnInfo(name = "next_due")
    val nextDue: Int,

    /** 0, 1 or 3. */
    @ColumnInfo(name = "remind_days_before", defaultValue = "1")
    val remindDaysBefore: Int = 1,

    /** "Remind me and add to Inbox"; off is "Remind me" only. */
    @ColumnInfo(name = "to_inbox", defaultValue = "1")
    val toInbox: Boolean = true,

    @ColumnInfo(name = "paused", defaultValue = "0")
    val paused: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * What one due date of an item turned into (V2-PLAN §4.2): still `WAITING`,
 * `MATCHED` to something captured, a `CANDIDATE` written to the Inbox, or
 * `SKIPPED` (nothing seen, and the item only reminds).
 *
 * [pendingId] and [entryId] have no foreign keys: a candidate or an entry may
 * be discarded, binned or purged, and the log keeps saying what happened.
 */
@Entity(
    tableName = "recurring_occurrence",
    foreignKeys = [
        ForeignKey(
            entity = RecurringItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["item_id", "due_date"], unique = true),
        // "Is this entry already some item's?" -- one entry answers one due date.
        Index(value = ["entry_id"]),
    ],
)
public data class RecurringOccurrenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "due_date")
    val dueDate: Int,

    /** `WAITING`, `MATCHED`, `CANDIDATE` or `SKIPPED`. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    @ColumnInfo(name = "pending_id")
    val pendingId: String?,

    @ColumnInfo(name = "entry_id")
    val entryId: String?,

    @ColumnInfo(name = "reminded_at")
    val remindedAt: Long?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/** "Not recurring" on a suggestion: this merchant, in this book, is not offered again. */
@Entity(
    tableName = "recurring_dismissal",
    primaryKeys = ["merchant_id", "ledger"],
)
public data class RecurringDismissalEntity(
    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    @ColumnInfo(name = "ledger")
    val ledger: LedgerType,

    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long,
)
