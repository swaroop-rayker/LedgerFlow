package com.ledgerflow.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 -> v3: `draft_entry` may hold many in-flight entries per ledger.
 *
 * v2 carried `UNIQUE(ledger, editing_entry_key)` (D-06), which allowed exactly
 * one new-entry draft per book. ADR-0013 supersedes that: the constraint read
 * as data loss in use, because starting a second entry silently resumed the
 * first rather than letting both exist. The stack screen answers the objection
 * D-06 actually raised — that unbounded drafts pile up where nobody finds them
 * — by showing them.
 *
 * **Index-only, and that is why there is no table rebuild here.** CLAUDE.md §7
 * requires `CREATE new / INSERT SELECT / DROP old / RENAME` rather than `ALTER`
 * chains, because an `ALTER` chain can half-apply and strand the schema. That
 * rule is about *reshaping* a table. Nothing here reshapes one: no column is
 * added, removed or retyped, and no row is rewritten. `DROP INDEX` and
 * `CREATE INDEX` are single statements, each atomic on its own, inside the
 * transaction Room wraps the migration in.
 *
 * No data is at risk either way — every existing draft row survives untouched,
 * which matters because those rows are unsaved user input and the whole reason
 * `draft_entry` exists (BUG6).
 *
 * The DDL is copied verbatim from the `createSql` Room emitted into
 * `schemas/3.json`. Room validates the live database against that JSON on every
 * open, so a hand-written approximation differing by a column order or an index
 * name throws `IllegalStateException` on every launch after the upgrade.
 * `MigrationV2ToV3Test` fails here instead of there.
 */
public val MIGRATION_2_3: Migration = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // The constraint ADR-0013 removes.
        db.execSQL("DROP INDEX IF EXISTS `index_draft_entry_unique_slot`")

        // The same pair, without the uniqueness -- still the lookup the
        // repository does to find an entry's edit-draft before reusing it.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_ledger_editing_entry_key` " +
                "ON `draft_entry` (`ledger`, `editing_entry_key`)",
        )

        // The stack reads one book's drafts newest-first.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_entry_ledger_updated_at` " +
                "ON `draft_entry` (`ledger`, `updated_at`)",
        )
    }
}
