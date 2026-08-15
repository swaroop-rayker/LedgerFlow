package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money

/**
 * The ledger, partitioned by [ledger] (ADR-0002).
 *
 * One table rather than two, because with two entry tables `line_item.entry_id`
 * and `attachment.entry_id` could not be foreign keys at all -- SQLite FKs
 * reference exactly one parent -- and losing `PRAGMA foreign_key_check` after
 * migrations is a worse trade than a query-shape invariant that tooling can
 * check.
 *
 * Every index leads with `ledger`, so the partition is real in the B-tree: a
 * debit query never traverses credit rows.
 *
 * SPEC.md §6.1 shows a `CHECK (ledger IN ('DEBIT','CREDIT'))`. Room cannot
 * express CHECK constraints, and SQLite cannot add one after CREATE TABLE. The
 * constraint is instead carried by the type: [ledger] is a [LedgerType] enum
 * and the converter can only ever write those two names. See §6.1.1.
 */
@Entity(
    tableName = "ledger_entry",
    indices = [
        Index(value = ["ledger", "local_date"]),
        Index(value = ["ledger", "category_id", "local_date"]),
        Index(value = ["ledger", "merchant_id", "local_date"]),
        Index(value = ["source_ref_id"]),
        // Standalone FK indices. The composite indices above lead with
        // `ledger`, so they cannot serve a lookup by merchant or payment method
        // alone -- which is exactly what SQLite does when the parent row is
        // modified. Without these, every merchant edit is a full table scan of
        // the ledger.
        Index(value = ["merchant_id"]),
        Index(value = ["payment_method_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchant_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PaymentMethodEntity::class,
            parentColumns = ["id"],
            childColumns = ["payment_method_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
public data class LedgerEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "ledger")
    val ledger: LedgerType,

    /** Always positive, always base currency (SPEC.md §5.8). */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Money,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "original_amount_minor")
    val originalAmountMinor: Long?,

    @ColumnInfo(name = "original_currency")
    val originalCurrency: String?,

    /** Rate x 1e6, user-entered. NEVER auto-fetched -- Law 6. */
    @ColumnInfo(name = "fx_rate_micro")
    val fxRateMicro: Long?,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    /** Days since epoch, device tz at capture. Avoids tz math in SQL. */
    @ColumnInfo(name = "local_date")
    val localDate: Int,

    @ColumnInfo(name = "merchant_id")
    val merchantId: String?,

    @ColumnInfo(name = "category_id")
    val categoryId: String?,

    /**
     * Denormalised alongside [categoryId] so analytics can group without a
     * self-join. The invariant that its parent equals [categoryId] is enforced
     * in ApproveTransactionUseCase -- a SQLite CHECK cannot contain a subquery
     * (SPEC.md §6.1.1).
     */
    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String?,

    @ColumnInfo(name = "payment_method_id")
    val paymentMethodId: String?,

    @ColumnInfo(name = "note")
    val note: String?,

    @ColumnInfo(name = "source")
    val source: EntrySource,

    /** Audit trail back to the pending_transaction that produced this row. */
    @ColumnInfo(name = "source_ref_id")
    val sourceRefId: String?,

    @ColumnInfo(name = "is_recurring", defaultValue = "0")
    val isRecurring: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "line_item",
    indices = [Index(value = ["entry_id"]), Index(value = ["normalized_name"])],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class LineItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "entry_id")
    val entryId: String,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,

    /** 1.000 = 1000, so 0.5 kg is representable without floating point. */
    @ColumnInfo(name = "quantity_milli", defaultValue = "1000")
    val quantityMilli: Long = 1000L,

    @ColumnInfo(name = "unit_price_minor")
    val unitPriceMinor: Long?,

    @ColumnInfo(name = "total_minor")
    val totalMinor: Money,

    @ColumnInfo(name = "kind")
    val kind: LineItemKind,

    @ColumnInfo(name = "category_id")
    val categoryId: String?,

    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String?,
)

/**
 * Per-ledger read views (ADR-0002).
 *
 * DAOs read from these, never from `ledger_entry` directly. The predicate is
 * part of the object, so a read path cannot forget it -- which is the whole
 * mechanism that turns Law 2 from an intention into an invariant.
 */
@DatabaseView(
    viewName = "debit_entries",
    value = "SELECT * FROM ledger_entry WHERE ledger = 'DEBIT' AND deleted_at IS NULL",
)
public data class DebitEntryView(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "local_date") val localDate: Int,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "merchant_id") val merchantId: String?,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: String?,
    @ColumnInfo(name = "note") val note: String?,
)

@DatabaseView(
    viewName = "credit_entries",
    value = "SELECT * FROM ledger_entry WHERE ledger = 'CREDIT' AND deleted_at IS NULL",
)
public data class CreditEntryView(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "local_date") val localDate: Int,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "merchant_id") val merchantId: String?,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: String?,
    @ColumnInfo(name = "note") val note: String?,
)
