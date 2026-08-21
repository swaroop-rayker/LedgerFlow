package com.ledgerflow.core.data.vault

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.vault.StorageMaintenance
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * `VACUUM`, with the write-ahead log flushed first.
 *
 * Lifted out of `DefaultLedgerRepository` unchanged when a second caller
 * appeared (ADR-0016). It is one statement and a pragma, and every line of it
 * is load-bearing:
 *
 * Deliberately **not** inside `withTransaction`: SQLite refuses to VACUUM
 * inside one, and Room's transaction wrapper would silently put us there.
 *
 * The checkpoint ahead of it is the same `TRUNCATE` the app already runs when
 * it backgrounds (`WalCheckpointObserver`, BUG2). Without it the deletes may
 * still be sitting in `-wal`, and VACUUM would rewrite a main database that
 * does not yet contain them -- reclaiming nothing and leaving the freed pages
 * exactly where they were.
 *
 * It rewrites the whole encrypted database, so a mistake here does not fail
 * loudly: it surfaces as an unreadable vault on the user's next launch
 * (`CLAUDE.md` §7). `PurgeDeletedEntriesTest` and `TaxonomyPurgeTest` both
 * re-open and read the vault afterwards for that reason.
 */
@Singleton
public class DefaultStorageMaintenance @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : StorageMaintenance {

    override suspend fun compactStorage(): Unit = withContext(io) {
        val database = session.requireDatabase().openHelper.writableDatabase
        database.query("PRAGMA wal_checkpoint(TRUNCATE);").use { it.moveToFirst() }
        database.execSQL("VACUUM")
    }
}
