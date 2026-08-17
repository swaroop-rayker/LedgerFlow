package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.LedgerType

/**
 * In-flight entry-form state (SPEC.md §6.1.2, D-06). BUG6's countermeasure.
 *
 * **Not the ledger.** Nothing here has been saved by the user, nothing here is
 * visible to any ledger query, and nothing here counts towards a total. It
 * exists so that a process death between the first keystroke and the Save tap
 * costs the user nothing.
 *
 * One row per in-flight entry rather than a singleton: a singleton silently
 * destroys the first draft when a second is started, which is BUG6 reintroduced
 * by BUG6's own countermeasure. Uniqueness is scoped instead, so drafts cannot
 * accumulate unbounded — see [editingEntryKey].
 *
 * As with `ledger_entry`, SPEC.md's `CHECK (ledger IN ('DEBIT','CREDIT'))` is
 * carried by the type: [ledger] is a [LedgerType] and the converter can only
 * ever write those two names.
 */
@Entity(
    tableName = "draft_entry",
    indices = [
        // One new-entry draft per ledger, one edit-draft per entry.
        Index(
            value = ["ledger", "editing_entry_key"],
            unique = true,
            name = "index_draft_entry_unique_slot",
        ),
        // "Resume unsaved entry?" wants the most recent first. SQLite scans an
        // index backwards as cheaply as forwards, so a plain index serves the
        // DESC ordering SPEC.md §6.1 asks for.
        Index(value = ["updated_at"]),
        Index(value = ["editing_entry_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["editing_entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class DraftEntryEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Authoritative. Deliberately absent from [payloadJson] so the two cannot disagree. */
    @ColumnInfo(name = "ledger")
    val ledger: LedgerType,

    /** Null for a new entry; set when this draft is an in-flight edit. */
    @ColumnInfo(name = "editing_entry_id")
    val editingEntryId: String?,

    /**
     * `COALESCE(editing_entry_id, '')`, the same sentinel pattern as
     * `category.parent_key` and for the same reason: SQLite treats NULLs as
     * distinct in a unique index, so a nullable column in the constraint would
     * let unlimited new-entry drafts coexist and make the index decorative.
     */
    @ColumnInfo(name = "editing_entry_key")
    val editingEntryKey: String,

    /**
     * The whole form, including line items.
     *
     * JSON rather than typed columns because a draft is partial by definition —
     * an amount mid-keystroke, no category yet — so every typed column would
     * have to be nullable anyway. The decisive argument is the write path: the
     * multi-line-item editor would make each 300 ms debounce tick a multi-row
     * transaction, where this is a single-row upsert.
     */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    /**
     * Form-state format version.
     *
     * A draft whose version is unrecognised is not offered for resume and is
     * never deserialized — but the row is kept. The app does not destroy user
     * input to tidy up after itself.
     */
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * Alternate spellings that resolve to one merchant (SPEC.md §5.5).
 *
 * `merchant.normalized_key` handles the mechanical variants — case, punctuation,
 * a trailing store number. This table handles the ones no normaliser can infer:
 * that "BBAZAAR" and "Big Bazaar" are the same shop is knowledge, not a string
 * transformation. Populated by the user at review time, and read by ingest at P2.
 */
@Entity(
    tableName = "merchant_alias",
    indices = [
        Index(value = ["normalized_alias"], unique = true),
        Index(value = ["merchant_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class MerchantAliasEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    /** As the user typed or as the SMS spelled it. Kept for display. */
    @ColumnInfo(name = "alias")
    val alias: String,

    /** `MerchantNormalizer.normalize(alias)`. The unique key. */
    @ColumnInfo(name = "normalized_alias")
    val normalizedAlias: String,
)

/**
 * A many-to-many rollup over categories (SPEC.md §5.5).
 *
 * The table ships at P1; the management UI does not. Its only consumer is
 * analytics (§5.6) at P3, so a CRUD screen now would be a surface with no
 * observable effect for two phases. Carrying the table early is nearly free and
 * saves a second migration; carrying the UI early is not.
 */
@Entity(
    tableName = "category_group",
    indices = [Index(value = ["ledger_scope"])],
)
public data class CategoryGroupEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int?,

    /** Groups are per-ledger, like the trees they roll up (Law 2). */
    @ColumnInfo(name = "ledger_scope")
    val ledgerScope: LedgerType,
)

@Entity(
    tableName = "category_group_member",
    primaryKeys = ["group_id", "category_id"],
    indices = [Index(value = ["category_id"])],
    foreignKeys = [
        ForeignKey(
            entity = CategoryGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class CategoryGroupMemberEntity(

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,
)
