package com.ledgerflow.core.database

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Checkpoints the write-ahead log when the app goes to the background.
 *
 * BUG2's second countermeasure. In WAL mode a committed transaction can live
 * only in `-wal` until SQLite decides to checkpoint. If the process is then
 * killed by the low-memory killer -- or the device reboots into an OS update --
 * the main database file alone does not contain those writes. To the user, that
 * is "the expense I entered yesterday is gone".
 *
 * `TRUNCATE` rather than `PASSIVE`: passive gives up if any reader is active,
 * which is exactly the moment we most need it to succeed.
 *
 * Register against `ProcessLifecycleOwner` so it fires once per app background,
 * not per Activity.
 */
public class WalCheckpointObserver(
    private val database: LedgerFlowDatabase,
    private val onError: (Throwable) -> Unit = {},
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        checkpoint()
    }

    /** Also called after batch writes. Safe to call on an already-clean WAL. */
    public fun checkpoint() {
        runCatching {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE);")
                .use { it.moveToFirst() }
        }.onFailure(onError)
    }
}
