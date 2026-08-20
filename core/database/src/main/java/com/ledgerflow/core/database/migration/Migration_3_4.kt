package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 -> v4: `draft_entry` carries a typed summary of what is in its payload.
 *
 * Three columns — `amount_minor`, `category_id`, `merchant_id` — denormalised
 * out of `payload_json`. They exist so the Ledger's unsaved section can show
 * what each draft is worth without reading the payload, which it cannot do:
 * `EntryDraftPayload` is `internal` to `:feature:entry`, and `DraftRepository`
 * treats the JSON as opaque deliberately, because `:core:domain` knowing a
 * screen's field names would invert the dependency the module graph exists to
 * keep pointing one way.
 *
 * **Table rebuild, not an `ALTER` chain** (CLAUDE.md §7). Three `ALTER TABLE
 * ADD COLUMN` statements would be shorter and would work, but the rule exists
 * because a chain can half-apply and strand the schema between two shapes —
 * and unlike `MIGRATION_2_3`, which touched only indices, this one genuinely
 * reshapes the table. The `CREATE new / INSERT SELECT / DROP old / RENAME`
 * sequence has exactly one moment where the table is not its old self, and it
 * is inside the transaction Room wraps this in.
 *
 * **Existing drafts keep their payload and get a zeroed summary.** That is
 * correct rather than lossy: the payload is authoritative, so nothing is lost,
 * and the next debounce write from the form fills the summary in. A draft the
 * user never touches again shows `₹0.00` in the unsaved section until they open
 * it — which is exactly what a draft with no amount typed shows anyway. §6.1.2
 * is explicit that the app does not destroy user input to tidy up after itself,
 * so back-filling by parsing the payload in SQL was never on the table: SQLite
 * cannot read that JSON, and guessing would be worse than a zero.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/4.json`. Room validates the live database against that JSON on every
 * open, so a hand-written approximation differing by a column order, a default
 * or an index name throws `IllegalStateException` on every launch after the
 * upgrade. `MigrationV3ToV4Test` fails here instead of there.
 *
 * The foreign key to `ledger_entry` is preserved. The three new columns
 * deliberately have **no** foreign keys of their own — a draft is unsaved and
 * invalid by definition, and an FK would let a category soft-deleted mid-typing
 * refuse the next 300 ms write. Losing keystrokes to referential integrity is
 * BUG6 arriving through BUG6's own countermeasure.
 */
public val MIGRATION_3_4: Migration = object : Migration(3, 4) {

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
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`editing_entry_id`) REFERENCES `ledger_entry`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

        // Columns named explicitly rather than `SELECT *`: the new table has a
        // different arity, and a positional copy would silently misalign if the
        // old shape were ever anything other than what this migration expects.
        db.execSQL(
            "INSERT INTO `draft_entry_new` (" +
                "`id`, `ledger`, `editing_entry_id`, `editing_entry_key`, " +
                "`payload_json`, `payload_version`, `created_at`, `updated_at`) " +
                "SELECT `id`, `ledger`, `editing_entry_id`, `editing_entry_key`, " +
                "`payload_json`, `payload_version`, `created_at`, `updated_at` " +
                "FROM `draft_entry`",
        )

        db.execSQL("DROP TABLE `draft_entry`")
        db.execSQL("ALTER TABLE `draft_entry_new` RENAME TO `draft_entry`")

        // Indices do not survive the rename; they belonged to the dropped
        // table. Recreated exactly as `schemas/4.json` spells them, including
        // the names -- Room compares those too.
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
