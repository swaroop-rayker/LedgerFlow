package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 → v14: `attachment.removed_from_phone_at` (V2-5, ADR-0035 §3).
 *
 * When a receipt left the phone because Google Drive holds a checked copy of
 * it: the file is gone from `filesDir/attachments/` and the row stays, so every
 * later `.lfbk` still lists it and a restore can bring it back. Null for every
 * existing row, which is what "on this phone" means.
 *
 * **A rebuild, not `ALTER TABLE ADD COLUMN`** (`CLAUDE.md` §7): create the new
 * table, copy every row, drop the old one, rename, then the two indices as v11
 * made them. `attachment` is a child of `ledger_entry` and nothing points at
 * it, so dropping it cascades nothing; foreign keys are off while migrations
 * run, and `PreMigrationGuard` runs `foreign_key_check` afterwards.
 * `MigrationV13ToV14Test` holds that every row and its link to its entry survive.
 */
public val MIGRATION_13_14: Migration = object : Migration(13, 14) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachment_new` (`id` TEXT NOT NULL, `entry_id` TEXT, " +
                "`file_path` TEXT NOT NULL, `mime` TEXT NOT NULL, `sha256` TEXT NOT NULL, " +
                "`bytes` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `removed_from_phone_at` INTEGER, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`entry_id`) REFERENCES `ledger_entry`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `attachment_new` (`id`, `entry_id`, `file_path`, `mime`, `sha256`, `bytes`, " +
                "`created_at`, `removed_from_phone_at`) " +
                "SELECT `id`, `entry_id`, `file_path`, `mime`, `sha256`, `bytes`, `created_at`, NULL " +
                "FROM `attachment`",
        )
        db.execSQL("DROP TABLE `attachment`")
        db.execSQL("ALTER TABLE `attachment_new` RENAME TO `attachment`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_entry_id` ON `attachment` (`entry_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_sha256` ON `attachment` (`sha256`)")
    }
}
