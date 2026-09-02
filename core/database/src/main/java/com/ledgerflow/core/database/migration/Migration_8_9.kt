package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 -> v9: `budget` and `daily_rollup` arrive (SPEC.md §5.6, §5.7, §6.1).
 *
 * The first schema change since P2-7, and the first one that opens a phase
 * rather than closing a bug. Both tables were named in §6.1 from the start and
 * deliberately stayed out of the schema until something wrote to them — an
 * unused table is a migration liability, carried through every future migration
 * whether it holds data or not.
 *
 * **Purely additive: nothing existing is read, rewritten or dropped.** That is
 * worth stating precisely, because `CLAUDE.md` §7 requires
 * `CREATE new / INSERT SELECT / DROP old / RENAME` and this migration performs
 * none of those. The rule governs *altering* a table — it exists because an
 * `ALTER` chain can half-apply and strand the schema with no way back. There is
 * no table being altered here, so there is nothing to rebuild and no partial
 * state to strand: two `CREATE TABLE`s and two `CREATE INDEX`es either run or
 * they do not. A future migration that adds a column to either of these tables
 * gets the full rebuild, exactly as `MIGRATION_7_8` did.
 *
 * **The asymmetry between the two tables is the thing to hold on to.**
 * `daily_rollup` is derived — every row is reproducible from `ledger_entry`
 * joined to `line_item` (ADR-0006), so a wrong rollup is a rebuild away and
 * this half of the migration is close to unfailable. `budget` is **user
 * intent**, and nothing in the app can reconstruct it: not the ledger, not the
 * parser, not a `.lfbk` written before v9. It is empty on the way in here,
 * which makes *this* migration safe, and it is why the table is in the backup
 * payload from the day it exists rather than from the day the feature ships.
 *
 * **Both tables start empty and stay empty until P3's writers land.** No
 * backfill of `daily_rollup` runs here: it would double the work of a
 * migration that must be fast on the upgrade path (§8.1's Upgrading screen),
 * and ADR-0006's reconciliation pass rebuilds the whole table from the base
 * tables anyway — so a backfill would be a second implementation of the one
 * routine that ADR exists to keep singular. An empty rollup table is not a
 * wrong rollup table; it is a cold cache, and the first reconciliation fills
 * it.
 *
 * **No foreign keys are declared**, on either table. `budget.category_id` is
 * unkeyed for the same reason `ledger_entry.category_id` is (ADR-0016) — the
 * reassign-or-block rule for taxonomy deletion lives in code, and a
 * `SET NULL`/`CASCADE` here would silently pre-empt it. So
 * `PRAGMA foreign_key_check` has nothing new to find after this migration,
 * which the test asserts rather than assumes.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/9.json`, with `${TABLE_NAME}` substituted; Room validates the live
 * database against exactly that on every open.
 */
public val MIGRATION_8_9: Migration = object : Migration(8, 9) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // §5.7 — user intent. Debit-only by omission: §6.1's DDL carries no
        // `ledger` column, so the Law 2 obligation sits on the reads instead,
        // and `LedgerIsolationTest` is extended to `daily_rollup` to hold it.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget` (`id` TEXT NOT NULL, " +
                "`category_id` TEXT NOT NULL, `subcategory_id` TEXT, " +
                "`period` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                "`start_date` INTEGER NOT NULL, " +
                "`rollover_enabled` INTEGER NOT NULL DEFAULT 0, " +
                "`alert_thresholds` TEXT NOT NULL DEFAULT '80,100', " +
                "`deleted_at` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_budget_category_id` " +
                "ON `budget` (`category_id`)",
        )

        // §5.6 — the materialized analytics table. `''` is the "dimension does
        // not apply" sentinel and never NULL (§6.1.1): SQLite treats NULLs as
        // distinct inside a composite key, so a nullable dimension would fan
        // one logical bucket out into rows that can never merge.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_rollup` (" +
                "`local_date` INTEGER NOT NULL, `ledger` TEXT NOT NULL, " +
                "`category_id` TEXT NOT NULL DEFAULT '', " +
                "`subcategory_id` TEXT NOT NULL DEFAULT '', " +
                "`merchant_id` TEXT NOT NULL DEFAULT '', " +
                "`payment_method_id` TEXT NOT NULL DEFAULT '', " +
                "`sum_minor` INTEGER NOT NULL, `txn_count` INTEGER NOT NULL, " +
                "PRIMARY KEY(`local_date`, `ledger`, `category_id`, " +
                "`subcategory_id`, `merchant_id`, `payment_method_id`))",
        )
        // The primary key leads with `local_date`, so on its own it serves
        // "one book, a date range" as a scan filtered by ledger. This index
        // leads with `ledger` and makes the partition physical in the B-tree,
        // which is what ADR-0002 asks of every index on a partitioned read --
        // and what §11's 5Y < 300 ms budget is going to need.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_daily_rollup_ledger_local_date` " +
                "ON `daily_rollup` (`ledger`, `local_date`)",
        )
    }
}
