package com.ledgerflow.core.domain.vault

/**
 * Reclaiming the space a destroy freed.
 *
 * This was a method on `LedgerRepository`, whose own doc said why and when it
 * would move:
 *
 * > It lives on this interface rather than behind a maintenance port of its own
 * > for the reason `baseCurrency` does: the purge is its only caller, and
 * > inventing a repository to hold one statement would be a layer that exists
 * > to hold a constant. **Give it its own port when something else needs it.**
 *
 * ADR-0016 is that something else. A taxonomy purge erases a name the user
 * typed, so it has the same obligation to make the bytes go and no business
 * reaching through the ledger's port to discharge it.
 */
public interface StorageMaintenance {

    /**
     * Rewrites the database file, reclaiming the space freed by a purge.
     *
     * `DELETE` marks pages free; it does not zero them and it does not shrink
     * the file. Without this, "permanently erase" would be true of the app's
     * queries and false of the bytes on disk -- which is the half a user asking
     * for permanence actually cares about.
     *
     * **Costly and delicate, in that order.** It rewrites the *entire*
     * encrypted database, so callers run it once at the end of a batch rather
     * than per row, and skip it when nothing was destroyed. A mistake here does
     * not fail loudly; it surfaces as an unreadable vault on the next launch,
     * which is why every test that triggers it re-opens and reads the vault
     * afterwards (`CLAUDE.md` §7).
     */
    public suspend fun compactStorage()
}
