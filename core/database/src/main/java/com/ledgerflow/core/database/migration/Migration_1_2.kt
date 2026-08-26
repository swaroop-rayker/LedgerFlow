package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: `draft_entry`, `merchant_alias`, `category_group`,
 * `category_group_member`.
 *
 * **Purely additive.** No existing table is touched, no column is added to one,
 * and no row is rewritten. CLAUDE.md §7 requires `CREATE new / INSERT SELECT /
 * DROP old / RENAME` rather than `ALTER` chains precisely because an `ALTER`
 * chain can half-apply and strand the schema — but that rule is about
 * *reshaping* a table. There is nothing here to reshape: four `CREATE TABLE`
 * statements, each atomic on its own, inside the transaction Room wraps the
 * migration in.
 *
 * The two ledger views are deliberately **not** dropped and recreated. §6.1.1
 * requires that only when a migration alters `ledger_entry`; this one does not,
 * so recreating them would be churn on the one object whose definition Law 2
 * depends on.
 *
 * The DDL below is copied verbatim from the `createSql` Room emitted into
 * `schemas/2.json`. That is not laziness — Room validates the live database
 * against that JSON on every open, and a hand-written approximation that
 * differs by so much as a column order throws `IllegalStateException` on every
 * launch after the upgrade. `MigrationV1ToV2Test` fails here rather than there.
 */
public val MIGRATION_1_2: Migration = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        createDraftEntry(db)
        createMerchantAlias(db)
        createCategoryGroups(db)
    }

    private fun createDraftEntry(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `draft_entry` (" +
                "`id` TEXT NOT NULL, " +
                "`ledger` TEXT NOT NULL, " +
                "`editing_entry_id` TEXT, " +
                "`editing_entry_key` TEXT NOT NULL, " +
                "`payload_json` TEXT NOT NULL, " +
                "`payload_version` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`editing_entry_id`) REFERENCES `ledger_entry`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_entry_unique_slot` " +
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

    private fun createMerchantAlias(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_alias` (" +
                "`id` TEXT NOT NULL, " +
                "`merchant_id` TEXT NOT NULL, " +
                "`alias` TEXT NOT NULL, " +
                "`normalized_alias` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`merchant_id`) REFERENCES `merchant`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_alias_normalized_alias` " +
                "ON `merchant_alias` (`normalized_alias`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_merchant_alias_merchant_id` " +
                "ON `merchant_alias` (`merchant_id`)",
        )
    }

    private fun createCategoryGroups(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `category_group` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`color_argb` INTEGER, " +
                "`ledger_scope` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_category_group_ledger_scope` " +
                "ON `category_group` (`ledger_scope`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `category_group_member` (" +
                "`group_id` TEXT NOT NULL, " +
                "`category_id` TEXT NOT NULL, " +
                "PRIMARY KEY(`group_id`, `category_id`), " +
                "FOREIGN KEY(`group_id`) REFERENCES `category_group`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_category_group_member_category_id` " +
                "ON `category_group_member` (`category_id`)",
        )
    }
}

/**
 * Every migration, in order.
 *
 * Room is given this array and nothing else — in particular it is never given a
 * destructive fallback (Law 4). A missing migration therefore fails loudly at
 * open time, which is the outcome BUG8 wants: an upgrade that refuses is
 * recoverable, an upgrade that wipes is not.
 */
public val LedgerFlowMigrations: Array<Migration> =
    arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7,
    )
