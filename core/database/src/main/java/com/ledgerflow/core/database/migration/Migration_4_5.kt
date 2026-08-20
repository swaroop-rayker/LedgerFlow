package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 -> v5: `draft_entry` also remembers when its entry is dated.
 *
 * One more column, `occurred_at`, denormalised out of `payload_json` beside the
 * three `MIGRATION_3_4` added. It exists so the Ledger's unsaved section can
 * show a date and time on a pending row the way it does on a committed one —
 * and it has to be a column for the same reason the others do: the payload's
 * shape is `:feature:entry`'s business, and `DraftRepository` treats the JSON
 * as opaque so that `:core:domain` never learns a screen's field names.
 *
 * **A separate migration rather than a wider v4, deliberately.** v4 had already
 * run on a real device by the time this column was asked for, so amending it
 * would have left that database claiming v4 with a shape the code no longer
 * recognises — Room rejects the open and the user meets the Recovery screen.
 * Migrations are append-only for the same reason committed schema JSONs are.
 *
 * Table rebuild, not `ALTER TABLE ADD COLUMN` (CLAUDE.md §7). One added column
 * is exactly the case where the shortcut looks safe, which is why the rule is
 * unconditional: an `ALTER` chain can half-apply and strand the schema, and
 * this table holds unsaved user input (BUG6).
 *
 * Existing drafts keep their payload and get `occurred_at = 0`. The reader
 * falls back to `updated_at` for those rather than rendering 1 January 1970 —
 * see `DraftSummaryRow`. Back-filling in SQL was never possible: SQLite cannot
 * read the JSON, and §6.1.2 forbids touching user input to tidy up.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/5.json`; Room validates the live database against it on every open,
 * so a hand-written approximation differing by a default or an index name
 * throws on every launch after the upgrade. `MigrationV4ToV5Test` fails here
 * instead of there.
 */
public val MIGRATION_4_5: Migration = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `draft_entry_new` (" +
                "`id` TEXT NOT NULL, " +
                "`ledger` TEXT NOT NULL, " +
                "`editing_entry_id` TEXT, " +
                "`editing_entry_key` TEXT NOT NULL, " +
                "`payload_json` TEXT NOT NULL, " +
                "`payload_version` INTEGER NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL DEFAULT 0, " +
                "`category_id` TEXT, " +
                "`merchant_id` TEXT, " +
                "`occurred_at` INTEGER NOT NULL DEFAULT 0, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`editing_entry_id`) REFERENCES `ledger_entry`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

        // Columns named explicitly. The new table has a different arity, and a
        // positional copy would misalign silently.
        db.execSQL(
            "INSERT INTO `draft_entry_new` (" +
                "`id`, `ledger`, `editing_entry_id`, `editing_entry_key`, " +
                "`payload_json`, `payload_version`, `amount_minor`, " +
                "`category_id`, `merchant_id`, `created_at`, `updated_at`) " +
                "SELECT `id`, `ledger`, `editing_entry_id`, `editing_entry_key`, " +
                "`payload_json`, `payload_version`, `amount_minor`, " +
                "`category_id`, `merchant_id`, `created_at`, `updated_at` " +
                "FROM `draft_entry`",
        )

        db.execSQL("DROP TABLE `draft_entry`")
        db.execSQL("ALTER TABLE `draft_entry_new` RENAME TO `draft_entry`")

        // Indices belonged to the dropped table and do not survive the rename.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_ledger_updated_at` " +
                "ON `draft_entry` (`ledger`, `updated_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_ledger_editing_entry_key` " +
                "ON `draft_entry` (`ledger`, `editing_entry_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_updated_at` " +
                "ON `draft_entry` (`updated_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_editing_entry_id` " +
                "ON `draft_entry` (`editing_entry_id`)",
        )
    }
}
