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
 * by BUG6's own countermeasure.
 *
 * **Drafts are unbounded per ledger (ADR-0013, superseding D-06.)** v2 carried
 * `UNIQUE(ledger, editing_entry_key)`, which allowed exactly one new-entry
 * draft per book on the reasoning that unbounded drafts would pile up where
 * nobody could find them. In use that constraint read as data loss: starting a
 * second entry silently resumed the first, so the second one could not exist.
 * The answer to "nobody can find them" is a surface that shows them, which is
 * what the drafts stack is — not a constraint that forbids having two.
 *
 * As with `ledger_entry`, SPEC.md's `CHECK (ledger IN ('DEBIT','CREDIT'))` is
 * carried by the type: [ledger] is a [LedgerType] and the converter can only
 * ever write those two names.
 */
@Entity(
    tableName = "draft_entry",
    indices = [
        // The stack: one book's drafts, most recent first. SQLite scans an
        // index backwards as cheaply as forwards, so this serves the DESC
        // ordering without a second index.
        Index(value = ["ledger", "updated_at"]),
        // Finding the edit-draft for a given entry. Not unique any more, but
        // still the lookup the repository does before reusing a row.
        Index(value = ["ledger", "editing_entry_key"]),
        // The 30-day purge sweeps on `updated_at` alone, across both books.
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
     * `COALESCE(editing_entry_id, '')`.
     *
     * It was the sentinel half of a unique constraint that no longer exists
     * (ADR-0013). It stays because it is still the column the edit-draft
     * lookup matches on, and because a NULL-free key is what lets that lookup
     * be a plain equality rather than an `IS NULL` special case.
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

    /**
     * The draft's amount, denormalised out of [payloadJson] (schema v4).
     *
     * **The payload stays authoritative; this is a copy for one purpose.** The
     * Ledger's unsaved section has to show what each draft is worth, and it
     * cannot read the payload: `EntryDraftPayload` is `internal` to
     * `:feature:entry`, and `DraftRepository` treats the JSON as opaque on
     * purpose -- `:core:domain` knowing a screen's field names would invert the
     * dependency the module graph exists to keep pointing one way.
     *
     * So the writer -- the entry form, which does know the shape -- lifts these
     * three out on every save, and every reader gets typed columns. The
     * alternative was moving the form's payload model down into `:core:model`,
     * which reverses a documented decision to buy the same thing.
     *
     * Zero for a draft with no amount typed yet, which is most of a draft's
     * life. It is minor units like every other amount (Law 3).
     */
    @ColumnInfo(name = "amount_minor", defaultValue = "0")
    val amountMinor: Long = 0L,

    /**
     * Denormalised alongside [amountMinor], and **deliberately not a foreign
     * key**.
     *
     * A draft is unsaved, partial and invalid by definition; an FK here would
     * mean a category soft-deleted mid-typing could refuse the next debounce
     * write, and losing keystrokes to referential integrity is BUG6 arriving
     * through the countermeasure for BUG6. The summary query resolves the name
     * with a `LEFT JOIN` and shows nothing when it no longer resolves, which is
     * the honest rendering of "you picked something that has since gone".
     */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,

    @ColumnInfo(name = "merchant_id")
    val merchantId: String? = null,

    /**
     * When the draft says the entry happened, denormalised out of
     * [payloadJson] (schema v5).
     *
     * The date the user picked in the form -- what becomes `occurred_at` on the
     * committed row -- rather than [updatedAt], which is when they last typed.
     * The Ledger shows it on a pending row so an unsaved entry reads the same
     * way a saved one does.
     *
     * Zero for a draft written before v5, and for those the reader falls back
     * to [updatedAt]: rendering the epoch would be worse than approximately
     * right.
     */
    @ColumnInfo(name = "occurred_at", defaultValue = "0")
    val occurredAt: Long = 0L,

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
