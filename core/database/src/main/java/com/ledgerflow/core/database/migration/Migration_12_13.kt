package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 -> v13: merchant categories (V2-PLAN §5, V2-6; owner 2026-10-01).
 *
 * A new `merchant_category` table (flat, both books, soft-deleted with the same
 * `deleted_at` sentinel as `category`) and a nullable
 * `merchant.merchant_category_id`. **Every existing merchant keeps `NULL`**,
 * which is Uncategorised: categorising is the user's, in Organise, and a
 * migration that guessed would be filing their merchants for them (§5.2).
 * The twelve defaults are not inserted here; they are seeded once per vault at
 * launch (`SeedMerchantCategoriesUseCase`), which also covers a restored v12
 * backup and a vault whose user later deletes them all.
 *
 * **Rebuilt, not `ALTER`ed** (CLAUDE.md §7). This is the first rebuild of a
 * table that others point at: `ledger_entry.merchant_id` (`ON DELETE SET
 * NULL`) and `merchant_alias.merchant_id` (`ON DELETE CASCADE`). With foreign
 * keys enforced, `DROP TABLE merchant` runs an implicit `DELETE` first, and
 * that would strip every entry's merchant and delete every alias. They are off
 * while migrations run (`ForeignKeysCallback` switches them on in `onOpen`,
 * after), and the migration **refuses to run if they are not**: a loud failure
 * the pre-migration snapshot recovers from (ADR-0019), never quiet loss.
 * `MigrationV12ToV13Test` seeds aliases and entries and holds that both survive.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/13.json`; Room validates the live database against it on every open.
 */
public val MIGRATION_12_13: Migration = object : Migration(12, 13) {

    override fun migrate(db: SupportSQLiteDatabase) {
        check(!foreignKeysEnforced(db)) {
            "v13 rebuilds `merchant`, which entries and aliases reference; " +
                "with foreign keys on, dropping it would cascade. Refusing."
        }

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_category` (`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL COLLATE NOCASE, `color_argb` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, `is_system` INTEGER NOT NULL DEFAULT 0, " +
                "`deleted_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_category_unique_live_name` " +
                "ON `merchant_category` (`name`, `deleted_at`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_new` (`id` TEXT NOT NULL, " +
                "`canonical_name` TEXT NOT NULL, `normalized_key` TEXT NOT NULL, " +
                "`default_category_id` TEXT, `logo_ref` TEXT, " +
                "`deleted_at` INTEGER NOT NULL DEFAULT 0, `merchant_category_id` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `merchant_new` (`id`, `canonical_name`, `normalized_key`, " +
                "`default_category_id`, `logo_ref`, `deleted_at`, `merchant_category_id`) " +
                "SELECT `id`, `canonical_name`, `normalized_key`, `default_category_id`, " +
                "`logo_ref`, `deleted_at`, NULL FROM `merchant`",
        )
        db.execSQL("DROP TABLE `merchant`")
        db.execSQL("ALTER TABLE `merchant_new` RENAME TO `merchant`")

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_normalized_key` " +
                "ON `merchant` (`normalized_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_merchant_merchant_category_id` " +
                "ON `merchant` (`merchant_category_id`)",
        )
    }

    private fun foreignKeysEnforced(db: SupportSQLiteDatabase): Boolean =
        db.query("PRAGMA foreign_keys").use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
}
