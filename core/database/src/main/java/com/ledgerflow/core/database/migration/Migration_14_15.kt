package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 → v15: savings goals (V2-PLAN §4.1, V2-7; owner 2026-11-09).
 *
 * **Purely additive**, like v9 and v11: two `CREATE TABLE`s and their indices.
 * No existing table is read or rewritten, so there is nothing to copy and no
 * rebuild (`CLAUDE.md` §7's rebuild rule is about changing a table, and none
 * changes). `goal_contribution` points at `savings_goal`, created first;
 * `savings_goal.linked_category_id` has no foreign key, as `budget.category_id`
 * has none (ADR-0016). `PreMigrationGuard` runs `foreign_key_check` afterwards,
 * and `MigrationV14ToV15Test` holds that every v14 row is untouched.
 */
public val MIGRATION_14_15: Migration = object : Migration(14, 15) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `savings_goal` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`target_minor` INTEGER NOT NULL, `target_date` INTEGER, `linked_category_id` TEXT, " +
                "`count_from` INTEGER, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_savings_goal_linked_category_id` " +
                "ON `savings_goal` (`linked_category_id`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal_contribution` (`id` TEXT NOT NULL, `goal_id` TEXT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `local_date` INTEGER NOT NULL, `note` TEXT, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`goal_id`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_goal_contribution_goal_id_local_date` " +
                "ON `goal_contribution` (`goal_id`, `local_date`)",
        )
    }
}
