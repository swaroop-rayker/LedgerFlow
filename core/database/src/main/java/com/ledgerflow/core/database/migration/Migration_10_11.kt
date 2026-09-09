package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 -> v11: `attachment` and `item_category_memory` arrive (SPEC.md §5.3,
 * §6.1; ADR-0023). P4's schema, and all of it.
 *
 * **Purely additive: nothing existing is read, rewritten or dropped.**
 * `MIGRATION_8_9` is the precedent and its reasoning applies unchanged.
 * `CLAUDE.md` §7 requires `CREATE new / INSERT SELECT / DROP old / RENAME`
 * because an `ALTER` chain can half-apply and strand a schema with no way back
 * — that rule governs *altering* a table. There is no table being altered here,
 * so there is nothing to rebuild and no partial state to strand: two
 * `CREATE TABLE`s and three `CREATE INDEX`es either run or they do not. The
 * first migration that adds a column to either of these gets the full rebuild.
 *
 * **`pending_line_item` is deliberately absent** — ADR-0022. §6.1 carried it as
 * an elision and §13's P4 row promised it, and reading the code at P4 changed
 * the answer rather than supplying the presumed DDL: schema v8 already added
 * `pending_transaction.review_draft_json`, and `ReviewEdits` already carries
 * itemised lines with name, unit price, quantity and category. OCR's extracted
 * half rides `extracted_json`, which is a versioned payload for exactly the
 * reason that applies here — a candidate is partial by definition. Nothing
 * queries pending lines relationally, so a table would have cost a migration, a
 * `BackupPayload` list and a CSV writer for a structure with no reader.
 *
 * **The foreign key is why `ledger_entry` is one table.** `LedgerEntryEntity`'s
 * KDoc names `attachment.entry_id` as half the justification for partitioning
 * one table rather than keeping two: a SQLite foreign key references exactly
 * one parent, so with two entry tables this column could not be a key at all.
 * It is one here, which is what makes the `PRAGMA foreign_key_check` after this
 * migration mean something rather than pass vacuously.
 *
 * **Both tables start empty and stay empty until P4's writers land.** No
 * backfill is possible for either: there are no receipt images on disk to
 * enumerate, and `item_category_memory` is learned from user confirmations that
 * have not happened. An empty suggestion table is not a wrong one — it is a
 * memory with nothing in it yet, which is the honest state for an install that
 * predates OCR.
 *
 * **`attachment` rows without files, and files without rows.** Neither exists
 * after this migration, and both are possible later: the cascade from
 * `ledger_entry` removes rows and leaves bytes (see `AttachmentDao`), and a
 * restore that arrives without its sibling image directory produces rows whose
 * files are absent by design (ADR-0023). The schema cannot prevent either; what
 * it can do is not pretend otherwise, which is why `file_path` is relative and
 * `bytes` records the plaintext length rather than what is on disk.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/11.json`; Room validates the live database against it on every open,
 * so a hand-written variant that differs by a space fails at launch.
 */
public val MIGRATION_10_11: Migration = object : Migration(10, 11) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachment` (" +
                "`id` TEXT NOT NULL, `entry_id` TEXT, `file_path` TEXT NOT NULL, " +
                "`mime` TEXT NOT NULL, `sha256` TEXT NOT NULL, " +
                "`bytes` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`entry_id`) REFERENCES `ledger_entry`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachment_entry_id` " +
                "ON `attachment` (`entry_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachment_sha256` " +
                "ON `attachment` (`sha256`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_category_memory` (" +
                "`merchant_id` TEXT NOT NULL, `normalized_item` TEXT NOT NULL, " +
                "`category_id` TEXT NOT NULL, `subcategory_id` TEXT, " +
                "`hit_count` INTEGER NOT NULL DEFAULT 1, " +
                "PRIMARY KEY(`merchant_id`, `normalized_item`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_item_category_memory_category_id` " +
                "ON `item_category_memory` (`category_id`)",
        )
    }
}
