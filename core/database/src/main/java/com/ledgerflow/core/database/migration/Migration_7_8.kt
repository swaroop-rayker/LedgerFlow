package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 -> v8: `pending_transaction` gains `review_draft_json` (SPEC.md §5.1, §5.4).
 *
 * **BUG6, applied to the Inbox.** The entry form persists to `draft_entry` on
 * every keystroke; the review screen held its typing in a ViewModel, so a back
 * press threw it away — the destination is popped, the ViewModel dies with it,
 * and `SavedStateHandle` does not survive that (it survives a configuration
 * change, which is a different thing). A process death lost it too. This column
 * is where that typing lands.
 *
 * **A column here rather than a row in `draft_entry`**, which is the shortcut
 * §5.4 exists to refuse: routing a candidate through the drafts stack would put
 * a half-reviewed message where discarding it in one place leaves it alive in
 * the other. The candidate is already a row; this is one more thing known about
 * it, and it dies with the row.
 *
 * **Rebuilt, not `ALTER`ed** (CLAUDE.md §7). One added column to a table nobody
 * has hand-edited is exactly the case where the shortcut looks safe, which is
 * why the rule is unconditional: an `ALTER` chain can half-apply and strand the
 * schema with no way back. The three indexes are recreated after the rename
 * because `DROP TABLE` takes them with it — a migration that rebuilt the table
 * and forgot them would leave a correct schema that Room validates happily and
 * a dedupe window that has become a table scan.
 *
 * **Every existing row keeps `NULL`,** which is the honest value: nobody has
 * typed anything into a review screen that could not save it. A row with null
 * here opens from the parser's extraction exactly as it did at v7, so the
 * upgrade changes nothing the user can see until they edit something.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/8.json`; Room validates the live database against it on every open.
 */
public val MIGRATION_7_8: Migration = object : Migration(7, 8) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_transaction_new` (`id` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `dedupe_key` TEXT NOT NULL, " +
                "`suppressed_by_id` TEXT, `raw_ref_id` TEXT, " +
                "`extracted_json` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                "`status` TEXT NOT NULL, `needs_manual_fill` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `reviewed_at` INTEGER, " +
                "`approved_entry_id` TEXT, `review_draft_json` TEXT, PRIMARY KEY(`id`))",
        )

        db.execSQL(
            "INSERT INTO `pending_transaction_new` (`id`, `source`, `dedupe_key`, " +
                "`suppressed_by_id`, `raw_ref_id`, `extracted_json`, `confidence`, " +
                "`status`, `needs_manual_fill`, `created_at`, `reviewed_at`, " +
                "`approved_entry_id`, `review_draft_json`) " +
                "SELECT `id`, `source`, `dedupe_key`, `suppressed_by_id`, `raw_ref_id`, " +
                "`extracted_json`, `confidence`, `status`, `needs_manual_fill`, " +
                "`created_at`, `reviewed_at`, `approved_entry_id`, NULL " +
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
