package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored receipt image (SPEC.md §5.3, §6.1, §7.1; ADR-0023). Schema v11.
 *
 * ## Where the bytes are, and under which key
 *
 * The row is metadata. The file lives at [filePath] under
 * `filesDir/attachments/`, AES-256-GCM under the **DEK** — never `cacheDir`,
 * never external storage (Law 5). In the user's SAF backup tree the same image
 * is written once more, sealed under a **phrase-derived** key, *beside* the
 * `.lfbk` rather than inside it (ADR-0023). That mirrors the database exactly:
 * DEK at rest on the device, phrase-derived the moment it can leave.
 *
 * ## [filePath] is relative, and that is load-bearing
 *
 * `filesDir` differs across a reinstall and across a restore onto another
 * device, so an absolute path here is a BUG1/BUG2 with a long fuse — it would
 * resolve on the machine that wrote it and nowhere else, and the symptom would
 * be a receipt that vanished rather than an error. §6.1 says only
 * `TEXT NOT NULL`, so the constraint is written here and asserted by
 * `AttachmentPathIsRelativeTest`.
 *
 * ## [sha256] is over the plaintext
 *
 * Computed before encryption. It dedupes the same receipt attached twice and
 * gives integrity a meaning; a hash of ciphertext under a fresh nonce is
 * different every time and dedupes nothing.
 *
 * ## The foreign key is the reason `ledger_entry` is one table
 *
 * `LedgerEntryEntity`'s KDoc names `attachment.entry_id` as half of why the
 * ledger is one partitioned table rather than two: with two entry tables this
 * column could not be a foreign key at all, since a SQLite FK references
 * exactly one parent. So it is one here, and `PRAGMA foreign_key_check` after
 * every migration means something for it.
 *
 * **Nullable**, because an attachment exists before the entry does: OCR writes
 * the image, `pending_transaction.raw_ref_id` points at it, and only approval
 * creates the `ledger_entry` to attach it to. A NULL child is exempt from the
 * constraint, which is the behaviour wanted rather than a gap in it.
 *
 * **`ON DELETE CASCADE`, with an obligation attached.** Soft delete is the
 * normal path and touches nothing here; the only hard delete is
 * `PurgeDeletedEntriesUseCase` (`CLAUDE.md` §7). When that gains attachment
 * awareness it **must collect these rows' [filePath]s before the delete and
 * unlink the files after**, or the cascade removes the row and orphans the
 * image forever — invisible, since nothing else enumerates that directory.
 * Recorded here because the purge is written in a different module and this is
 * where the constraint that creates the obligation lives.
 */
@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // The FK's own index. SQLite scans the child table on a parent delete
        // without one, which on a purge of many entries is a scan per entry.
        Index(value = ["entry_id"]),
        // Dedupe: "is this exact image already stored?" is a lookup by hash,
        // and it runs once per capture.
        Index(value = ["sha256"]),
    ],
)
public data class AttachmentEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Null until the candidate this image belongs to is approved. */
    @ColumnInfo(name = "entry_id")
    val entryId: String?,

    /** **Relative** to `filesDir/attachments/`. See the class KDoc. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "mime")
    val mime: String,

    /** SHA-256 of the **plaintext**, hex. See the class KDoc. */
    @ColumnInfo(name = "sha256")
    val sha256: String,

    /** Plaintext length. The sealed file on disk is larger by nonce + tag. */
    @ColumnInfo(name = "bytes")
    val bytes: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * Learned `(merchant, item) -> category` suggestions (SPEC.md §5.3). Schema v11.
 *
 * §5.3's "category memory": once the user has filed *Amul Taaza 500ml* at one
 * merchant, the next bill from that merchant suggests the same category. A
 * suggestion only — nothing here files anything, and Law 1 is untouched, since
 * the ledger is still only written by `ApproveTransactionUseCase`.
 *
 * ## `''` rather than NULL for [merchantId], and why it is not optional
 *
 * This is `daily_rollup`'s sentinel rule (§6.1, §6.1.1) applied to a second
 * composite key, for both of its reasons. Room requires primary-key fields to
 * be non-null, so a nullable column could not be part of this key at all. And
 * SQLite treats NULLs as **distinct** inside a unique index, so even where it
 * were allowed, every unfiled-merchant observation would land in its own row
 * and the memory would never accumulate a second hit for anything. `''` means
 * "no merchant", it collides with itself, and the bucket merges.
 *
 * ## No foreign keys, deliberately
 *
 * [merchantId] carries a sentinel that matches no `merchant.id`, so it cannot
 * be a foreign key. [categoryId] could be, and is not, for symmetry with the
 * same decision on `daily_rollup`: a hard-deleted category (ADR-0016 permits
 * one, behind a reassign-or-block rule) would cascade away learned suggestions
 * that are cheap to keep and mildly useful if that category is ever recreated.
 * A dangling id here suggests a category that no longer exists, and the caller
 * resolving the suggestion simply finds nothing — which is the correct outcome
 * and costs one lookup.
 *
 * ## [hitCount] is a count, not a score
 *
 * Integer, incremented on each confirmation. It exists so that a merchant's
 * dominant filing for an item wins over a one-off correction, and so a future
 * ranking has something to rank by. Law 3 does not apply — it is not money —
 * but it is an `Int` rather than a real for the ordinary reason that counting
 * things does not need a fraction.
 */
@Entity(
    tableName = "item_category_memory",
    primaryKeys = ["merchant_id", "normalized_item"],
    indices = [
        // "what has this category ever been suggested for", and the sweep a
        // taxonomy purge needs (ADR-0016).
        Index(value = ["category_id"]),
    ],
)
public data class ItemCategoryMemoryEntity(

    /** `''` when the observation had no merchant. See the class KDoc. */
    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    /** `ItemNameNormalizer`'s output, which is what makes two spellings one key. */
    @ColumnInfo(name = "normalized_item")
    val normalizedItem: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String?,

    @ColumnInfo(name = "hit_count", defaultValue = "1")
    val hitCount: Int = 1,
)
