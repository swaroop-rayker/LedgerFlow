package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v15 → v16: recurring items (V2-PLAN §4.2, V2-7; owner 2026-11-09).
 *
 * **Purely additive**: three `CREATE TABLE`s and their indices; no existing
 * table is read or rewritten. `recurring_occurrence` points at
 * `recurring_item`, created first. `PreMigrationGuard` runs
 * `foreign_key_check` afterwards, and `MigrationV15ToV16Test` holds that every
 * v15 row is untouched.
 */
public val MIGRATION_15_16: Migration = object : Migration(15, 16) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_item` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`ledger` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                "`amount_varies` INTEGER NOT NULL DEFAULT 0, `merchant_id` TEXT, `category_id` TEXT, " +
                "`subcategory_id` TEXT, `cadence` TEXT NOT NULL, `every_days` INTEGER, " +
                "`anchor_date` INTEGER NOT NULL, `next_due` INTEGER NOT NULL, " +
                "`remind_days_before` INTEGER NOT NULL DEFAULT 1, `to_inbox` INTEGER NOT NULL DEFAULT 1, " +
                "`paused` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_item_next_due` ON `recurring_item` (`next_due`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_occurrence` (`id` TEXT NOT NULL, `item_id` TEXT NOT NULL, " +
                "`due_date` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `pending_id` TEXT, `entry_id` TEXT, " +
                "`reminded_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`item_id`) REFERENCES `recurring_item`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_occurrence_item_id_due_date` " +
                "ON `recurring_occurrence` (`item_id`, `due_date`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_occurrence_entry_id` ON `recurring_occurrence` (`entry_id`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_dismissal` (`merchant_id` TEXT NOT NULL, " +
                "`ledger` TEXT NOT NULL, `dismissed_at` INTEGER NOT NULL, PRIMARY KEY(`merchant_id`, `ledger`))",
        )
    }
}
