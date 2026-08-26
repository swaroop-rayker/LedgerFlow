package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 -> v7: `parser_rule` gains `instrument_hint` (SPEC.md §5.1).
 *
 * One column, for a rule that knows how the money moved even when its messages
 * do not say so. A rule matching only GPay, PhonePe, Paytm and BHIM is
 * describing a UPI payment; carrying that on the rule is what keeps the
 * pipeline from inferring it downstream, which would be branching on source
 * (CLAUDE.md §0).
 *
 * **Rebuilt, not `ALTER`ed** (CLAUDE.md §7). One added column to a table nobody
 * has hand-edited is exactly the case where the shortcut looks safe, which is
 * why the rule is unconditional: an `ALTER` chain can half-apply and strand the
 * schema. v6 was additive and created new tables, so it needed no rebuild;
 * this changes an existing one, so it gets the full
 * CREATE / INSERT SELECT / DROP / RENAME.
 *
 * **Existing rules keep their `NULL`.** A shipped rule is replaced wholesale on
 * the next ruleset load, so the shipped set repopulates itself; a rule the user
 * wrote has no instrument to claim and null is the honest value. Back-filling
 * would mean guessing an instrument from a regex, which is precisely what this
 * column exists to stop the code doing.
 *
 * The DDL is copied verbatim from the `createSql` Room emits into
 * `schemas/7.json`; Room validates the live database against it on every open.
 */
public val MIGRATION_6_7: Migration = object : Migration(6, 7) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
        "CREATE TABLE IF NOT EXISTS `parser_rule_new` (`id` TEXT NOT NULL, " +
            "`ruleset_version` INTEGER NOT NULL, `priority` INTEGER NOT NULL, " +
            "`sender_pattern` TEXT NOT NULL, `body_pattern` TEXT NOT NULL, " +
            "`field_map_json` TEXT NOT NULL, `direction` TEXT, `instrument_hint` " +
            "TEXT, `confidence_base` REAL NOT NULL, `enabled` INTEGER NOT NULL, " +
            "`is_user_defined` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )

        db.execSQL(
        "INSERT INTO `parser_rule_new` (`id`, `ruleset_version`, `priority`, " +
            "`sender_pattern`, `body_pattern`, `field_map_json`, `direction`, " +
            "`instrument_hint`, `confidence_base`, `enabled`, `is_user_defined`) " +
            "SELECT `id`, `ruleset_version`, `priority`, `sender_pattern`, " +
            "`body_pattern`, `field_map_json`, `direction`, NULL, `confidence_base`, " +
            "`enabled`, `is_user_defined` FROM `parser_rule`",
        )

        db.execSQL(
        "DROP TABLE `parser_rule`",
        )

        db.execSQL(
        "ALTER TABLE `parser_rule_new` RENAME TO `parser_rule`",
        )

        db.execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_parser_rule_ruleset_version_enabled_priority` ON `parser_rule` " +
            "(`ruleset_version`, `enabled`, `priority`)",
        )
    }
}
