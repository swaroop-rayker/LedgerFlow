package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10: `budget` gains the memory its alerts need (SPEC.md §5.7).
 *
 * `last_alerted_threshold` and `alert_period_start`. Without them a threshold
 * crossing fires on every evaluation rather than once — a notification each
 * time the user approves anything, which is how a useful alert becomes one the
 * user turns off. The pair is what makes "crossed 80% *this period*" a question
 * with an answer.
 *
 * **On the row rather than in `app_meta`**, because it is per-budget state that
 * must die with the budget. A key-value entry would outlive a deleted budget
 * and would need sweeping up by hand, and nothing else in this schema keeps
 * per-row state anywhere but on the row.
 *
 * **A full rebuild, not an `ALTER`** (CLAUDE.md §7). Two added columns to a
 * table nobody has hand-edited is exactly the case where the shortcut looks
 * safe, which is why the rule is unconditional: an `ALTER` chain can half-apply
 * and strand the schema with no way back. `MIGRATION_8_9` was additive in the
 * other sense — it created new tables and touched nothing — and so performed no
 * rebuild; this one *does* touch an existing table, so it takes the long road.
 *
 * The index is recreated after the rename because `DROP TABLE` takes it with
 * it. A migration that rebuilt the table and forgot would leave a schema Room
 * validates happily and a per-category budget lookup that has become a table
 * scan — the same trap `MIGRATION_7_8` records.
 *
 * **Every existing row gets 0 for both**, which is the honest value: no budget
 * that predates alerting has had anything announced, and period start 0 (1 Jan
 * 1970) can never equal a real current period, so the first evaluation after
 * the upgrade treats every budget as un-alerted and starts from the truth.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/10.json`; Room validates the live database against it on every open.
 */
public val MIGRATION_9_10: Migration = object : Migration(9, 10) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_new` (`id` TEXT NOT NULL, " +
                "`category_id` TEXT NOT NULL, `subcategory_id` TEXT, " +
                "`period` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                "`start_date` INTEGER NOT NULL, " +
                "`rollover_enabled` INTEGER NOT NULL DEFAULT 0, " +
                "`alert_thresholds` TEXT NOT NULL DEFAULT '80,100', " +
                "`deleted_at` INTEGER, " +
                "`last_alerted_threshold` INTEGER NOT NULL DEFAULT 0, " +
                "`alert_period_start` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))",
        )

        db.execSQL(
            "INSERT INTO `budget_new` (`id`, `category_id`, `subcategory_id`, " +
                "`period`, `amount_minor`, `start_date`, `rollover_enabled`, " +
                "`alert_thresholds`, `deleted_at`, `last_alerted_threshold`, " +
                "`alert_period_start`) " +
                "SELECT `id`, `category_id`, `subcategory_id`, `period`, " +
                "`amount_minor`, `start_date`, `rollover_enabled`, " +
                "`alert_thresholds`, `deleted_at`, 0, 0 FROM `budget`",
        )

        db.execSQL("DROP TABLE `budget`")
        db.execSQL("ALTER TABLE `budget_new` RENAME TO `budget`")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_budget_category_id` " +
                "ON `budget` (`category_id`)",
        )
    }
}
