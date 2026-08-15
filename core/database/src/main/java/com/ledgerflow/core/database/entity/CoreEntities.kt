package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.PaymentMethodType

/**
 * Key/value metadata: schemaVersion, dekWrapVersion, lastBackupAt, canary
 * (SPEC.md §6.1).
 */
@Entity(tableName = "app_meta")
public data class AppMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,
) {
    public companion object {
        public const val KEY_SCHEMA_VERSION: String = "schemaVersion"
        public const val KEY_DEK_WRAP_VERSION: String = "dekWrapVersion"
        public const val KEY_LAST_BACKUP_AT: String = "lastBackupAt"
        public const val KEY_BASE_CURRENCY: String = "baseCurrency"

        /**
         * Written at initialisation and verified on every unlock (SPEC.md
         * §7.3). Catches a DEK/database mismatch after a restore, and a
         * partially applied rotation (§7.7).
         */
        public const val KEY_CANARY: String = "canary"
        public const val CANARY_VALUE: String = "LedgerFlow-canary-v1"
    }
}

/**
 * Two-level category tree, disjoint per ledger.
 *
 * The uniqueness constraint uses sentinels rather than a partial index, per
 * SPEC.md §6.1.1: Room's `@Index` has no `WHERE`, and SQLite treats NULLs as
 * distinct in a unique index, so nullable `parent_id`/`deleted_at` would let
 * two live top-level "Food" categories coexist. `parent_key` and a non-null
 * `deleted_at` make a plain unique index actually enforce it.
 */
@Entity(
    tableName = "category",
    indices = [
        Index(
            value = ["parent_key", "name", "ledger_scope", "deleted_at"],
            unique = true,
            name = "index_category_unique_live_name",
        ),
        Index(value = ["ledger_scope"]),
        Index(value = ["parent_id"]),
    ],
)
public data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Real nullable FK. Not declared as a Room @ForeignKey: a self-reference
     *  with ON DELETE SET NULL would fight the soft-delete model, and category
     *  deletion goes through a re-assign flow (SPEC.md §5.5). */
    @ColumnInfo(name = "parent_id")
    val parentId: String?,

    /** `COALESCE(parent_id, '')`. Maintained alongside [parentId]. */
    @ColumnInfo(name = "parent_key")
    val parentKey: String,

    @ColumnInfo(name = "ledger_scope")
    val ledgerScope: LedgerType,

    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "icon")
    val icon: String,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "is_system", defaultValue = "0")
    val isSystem: Boolean = false,

    /** 0 means live; otherwise the soft-delete timestamp in epoch millis. */
    @ColumnInfo(name = "deleted_at", defaultValue = "0")
    val deletedAt: Long = 0L,
)

@Entity(
    tableName = "merchant",
    indices = [Index(value = ["normalized_key"], unique = true)],
)
public data class MerchantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,

    @ColumnInfo(name = "normalized_key")
    val normalizedKey: String,

    @ColumnInfo(name = "default_category_id")
    val defaultCategoryId: String?,

    @ColumnInfo(name = "logo_ref")
    val logoRef: String?,

    @ColumnInfo(name = "deleted_at", defaultValue = "0")
    val deletedAt: Long = 0L,
)

@Entity(
    tableName = "payment_method",
    indices = [Index(value = ["last4"])],
)
public data class PaymentMethodEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "type")
    val type: PaymentMethodType,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "issuer")
    val issuer: String?,

    /** Used to auto-select the instrument from a parsed SMS (SPEC.md §5.5). */
    @ColumnInfo(name = "last4")
    val last4: String?,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int?,

    @ColumnInfo(name = "is_default", defaultValue = "0")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "deleted_at", defaultValue = "0")
    val deletedAt: Long = 0L,
)
