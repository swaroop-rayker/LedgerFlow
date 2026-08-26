package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 -> v6: the ingest side (SPEC.md §5.1, §5.2, §6.1). P2's foundation.
 *
 * Six new tables: the two raw capture tables, the two allowlists that decide
 * what may be read at all, the versioned parser ruleset, and
 * `pending_transaction` — the approval queue Law 1 exists to require.
 *
 * **Purely additive, and that is why there is no table rebuild here.**
 * CLAUDE.md §7 bans `ALTER` chains because they can half-apply and strand an
 * existing table's schema; creating a table that did not exist has no such
 * failure mode, and there is no data to copy. Every earlier migration in this
 * chain rebuilds because every earlier one changed a table that already held
 * user data. Nothing in v5 is touched by this one — `MigrationV5ToV6Test`
 * seeds a ledger and checks it comes through anyway, because "additive" is
 * exactly the assumption under which nobody looks.
 *
 * **No `pending_line_item`.** It is elided in §6.1 and stays that way until P4
 * (§16 Q7): a bank SMS and a UPI notification each carry one amount, so nothing
 * in the P2 pipeline can produce an itemised candidate. Adding it here would
 * mean guessing a shape against an OCR pipeline that does not exist, and
 * shipping a table nothing writes to for two phases.
 *
 * **No seed data.** The curated package allowlist (D-10) and the shipped parser
 * ruleset are loaded from assets by the code that owns them, on first run and
 * on version bump — not written here. A migration that seeded them would seed
 * nothing on a later ruleset bump, and would put the curated list in two places
 * that can disagree.
 *
 * The DDL below is copied verbatim from the `createSql` Room emits into
 * `schemas/6.json`. Room validates the live database against that on every
 * open, so a hand-written approximation differing by a nullability, a default
 * or an index name throws on every launch after the upgrade. The test fails
 * here instead of on the user's phone; verified by deleting one index and
 * watching it do so.
 *
 * One function per table because the whole thing is otherwise a single
 * eighty-line `migrate`, which detekt rejects and a reader skims.
 */
public val MIGRATION_5_6: Migration = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.createSmsRaw()
        db.createNotificationRaw()
        db.createPackageAllowlist()
        db.createSenderAllowlist()
        db.createParserRule()
        db.createPendingTransaction()
    }
}

/**
 * §5.1's capture table. Written by the receiver before anything parses, so the
 * unique `body_hash` is what makes a double network delivery a no-op at the
 * database rather than at a caller that might forget.
 */
private fun SupportSQLiteDatabase.createSmsRaw() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `sms_raw` (`id` TEXT NOT NULL, `sender` TEXT " +
            "NOT NULL, `body` TEXT NOT NULL, `body_hash` TEXT NOT NULL, `received_at` " +
            "INTEGER NOT NULL, `sim_slot` INTEGER, `parse_status` TEXT NOT NULL, " +
            "`matched_rule_id` TEXT, `retention_expires_at` INTEGER NOT NULL, PRIMARY " +
            "KEY(`id`))",
    )

    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_raw_body_hash` ON `sms_raw` " +
            "(`body_hash`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_sms_raw_parse_status_received_at` ON " +
            "`sms_raw` (`parse_status`, `received_at`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_sms_raw_retention_expires_at` ON " +
            "`sms_raw` (`retention_expires_at`)",
    )
}

/**
 * §5.2's capture table. A row here means the package was allowlisted — the
 * filter runs before the body is read, so there is no rejected state to store.
 */
private fun SupportSQLiteDatabase.createNotificationRaw() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `notification_raw` (`id` TEXT NOT NULL, " +
            "`package_name` TEXT NOT NULL, `title` TEXT, `body` TEXT NOT NULL, " +
            "`body_hash` TEXT NOT NULL, `posted_at` INTEGER NOT NULL, `parse_status` " +
            "TEXT NOT NULL, `matched_rule_id` TEXT, `retention_expires_at` INTEGER " +
            "NOT NULL, PRIMARY KEY(`id`))",
    )

    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_raw_body_hash` ON " +
            "`notification_raw` (`body_hash`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_notification_raw_parse_status_posted_at` ON `notification_raw` " +
            "(`parse_status`, `posted_at`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_notification_raw_retention_expires_at` " +
            "ON `notification_raw` (`retention_expires_at`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_notification_raw_package_name_posted_at` ON `notification_raw` " +
            "(`package_name`, `posted_at`)",
    )
}

/**
 * D-10's allowlist: the table that decides what may be read at all. Created
 * empty; the curated default is loaded from an asset by the code that owns it,
 * so the list does not live in two places that can disagree.
 */
private fun SupportSQLiteDatabase.createPackageAllowlist() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `package_allowlist` (`package_name` TEXT NOT " +
            "NULL, `label` TEXT, `enabled` INTEGER NOT NULL, PRIMARY " +
            "KEY(`package_name`))",
    )
}

/** §5.1's financial-sender allowlist, applied in the worker rather than the receiver. */
private fun SupportSQLiteDatabase.createSenderAllowlist() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `sender_allowlist` (`sender_pattern` TEXT NOT " +
            "NULL, `label` TEXT, `enabled` INTEGER NOT NULL, PRIMARY " +
            "KEY(`sender_pattern`))",
    )
}

/**
 * The versioned ruleset (§5.1). A table and not only an asset, because a
 * user-defined rule has nowhere else to live and a disabled shipped rule has to
 * survive the next ruleset load.
 */
private fun SupportSQLiteDatabase.createParserRule() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `parser_rule` (`id` TEXT NOT NULL, " +
            "`ruleset_version` INTEGER NOT NULL, `priority` INTEGER NOT NULL, " +
            "`sender_pattern` TEXT NOT NULL, `body_pattern` TEXT NOT NULL, " +
            "`field_map_json` TEXT NOT NULL, `direction` TEXT, `confidence_base` REAL " +
            "NOT NULL, `enabled` INTEGER NOT NULL, `is_user_defined` INTEGER NOT " +
            "NULL, PRIMARY KEY(`id`))",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_parser_rule_ruleset_version_enabled_priority` ON `parser_rule` " +
            "(`ruleset_version`, `enabled`, `priority`)",
    )
}

/**
 * The approval queue — the table Law 1 is about. Nothing here appears in a
 * total, a ledger query or a rollup until `ApproveTransactionUseCase` moves it.
 *
 * No foreign keys, deliberately: `approved_entry_id` points at a row
 * `PurgeDeletedEntries` can destroy and `suppressed_by_id` at one that can be
 * discarded and purged, so a cascade from either would delete the audit trail
 * rather than a stale pointer.
 */
private fun SupportSQLiteDatabase.createPendingTransaction() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `pending_transaction` (`id` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, `dedupe_key` TEXT NOT NULL, `suppressed_by_id` " +
            "TEXT, `raw_ref_id` TEXT, `extracted_json` TEXT NOT NULL, `confidence` " +
            "REAL NOT NULL, `status` TEXT NOT NULL, `needs_manual_fill` INTEGER NOT " +
            "NULL, `created_at` INTEGER NOT NULL, `reviewed_at` INTEGER, " +
            "`approved_entry_id` TEXT, PRIMARY KEY(`id`))",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_pending_transaction_status_created_at` " +
            "ON `pending_transaction` (`status`, `created_at`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_pending_transaction_dedupe_key_created_at` ON " +
            "`pending_transaction` (`dedupe_key`, `created_at`)",
    )

    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_pending_transaction_suppressed_by_id` " +
            "ON `pending_transaction` (`suppressed_by_id`)",
    )
}
