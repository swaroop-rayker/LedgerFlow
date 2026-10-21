package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v11 -> v12: `pending_transaction` gains `non_transaction_kind` (SPEC.md §5.1;
 * BUG58, V2-PLAN D6).
 *
 * **The ghost Inbox entries.** Allowlisted senders post one-time passwords,
 * balance notices, statement and due-date reminders, offers and failed-payment
 * notices; none matches a rule, and each became a confidence-0 candidate with
 * no payee and no amount. The classifier now recognises them, and this column
 * is where its label lands. The row is kept — §5.1's never-drop rule — and the
 * label moves it from the queue to the Inbox's "Not transactions" filter.
 *
 * **Rebuilt, not `ALTER`ed** (CLAUDE.md §7), exactly as `MIGRATION_7_8` rebuilt
 * this table for `review_draft_json`, and the three indexes are recreated after
 * the rename because `DROP TABLE` takes them with it.
 *
 * **Every existing row keeps `NULL`**, which is what "nothing was ever
 * classified" means. No back-fill: the classifier lives in `:feature:ingest`,
 * which this module may not see, and a migration that guessed at a label would
 * be moving rows out of the user's queue on a schema upgrade. A ghost that
 * predates v12 stays where it was until the user discards it.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/12.json`; Room validates the live database against it on every open.
 */
public val MIGRATION_11_12: Migration = object : Migration(11, 12) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_transaction_new` (`id` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `dedupe_key` TEXT NOT NULL, " +
                "`suppressed_by_id` TEXT, `raw_ref_id` TEXT, " +
                "`extracted_json` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                "`status` TEXT NOT NULL, `needs_manual_fill` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `reviewed_at` INTEGER, " +
                "`approved_entry_id` TEXT, `review_draft_json` TEXT, " +
                "`non_transaction_kind` TEXT, PRIMARY KEY(`id`))",
        )

        db.execSQL(
            "INSERT INTO `pending_transaction_new` (`id`, `source`, `dedupe_key`, " +
                "`suppressed_by_id`, `raw_ref_id`, `extracted_json`, `confidence`, " +
                "`status`, `needs_manual_fill`, `created_at`, `reviewed_at`, " +
                "`approved_entry_id`, `review_draft_json`, `non_transaction_kind`) " +
                "SELECT `id`, `source`, `dedupe_key`, `suppressed_by_id`, `raw_ref_id`, " +
                "`extracted_json`, `confidence`, `status`, `needs_manual_fill`, " +
                "`created_at`, `reviewed_at`, `approved_entry_id`, `review_draft_json`, NULL " +
                "FROM `pending_transaction`",
        )

        db.execSQL(
            "DROP TABLE `pending_transaction`",
        )

        db.execSQL(
            "ALTER TABLE `pending_transaction_new` RENAME TO `pending_transaction`",
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_pending_transaction_status_created_at` ON `pending_transaction` " +
                "(`status`, `created_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_pending_transaction_dedupe_key_created_at` ON " +
                "`pending_transaction` (`dedupe_key`, `created_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_pending_transaction_suppressed_by_id` ON `pending_transaction` " +
                "(`suppressed_by_id`)",
        )
    }
}
