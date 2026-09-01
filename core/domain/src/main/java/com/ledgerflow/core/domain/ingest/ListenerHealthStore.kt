package com.ledgerflow.core.domain.ingest

import kotlinx.coroutines.flow.Flow

/**
 * Where the notification listener's liveness is recorded (SPEC.md §5.2, ADR-0020).
 *
 * **Not the vault.** The writer is `NotificationIngestService.onListenerConnected`,
 * which the system calls when it binds the listener — at boot, in a process with
 * no Activity, routinely before the user has unlocked anything. `CLAUDE.md` §7
 * names that caller and its trap: `requireDatabase()` throws there, the throw is
 * swallowed by a `runCatching`, and the operation reports success while doing
 * nothing. That is BUG12 and BUG13, and this port exists so the third instance
 * does not happen here.
 *
 * ADR-0020 settles the storage and draws the line that keeps it narrow: this
 * store holds operational metadata about the app's own machinery — two
 * timestamps and a grant observation — and never financial data, message
 * content, or anything derived from either.
 *
 * A port in `:core:domain` rather than a class in `:core:datastore` for the
 * usual reason: `:feature:dashboard` renders the banner, `:feature:ingest`
 * writes the record, and features may not depend on features (`CLAUDE.md` §3).
 */
public interface ListenerHealthStore {

    /**
     * The record, re-emitted whenever any part of it changes.
     *
     * Includes [ListenerHealthRecord.connected], which is in-process state
     * rather than a stored value — the store owns both halves so that a
     * consumer never has to assemble a health picture from two sources and get
     * the assembly subtly wrong.
     */
    public val record: Flow<ListenerHealthRecord>

    /** The record right now. §5.2 confirms the grant by polling on resume, not by observing. */
    public suspend fun current(): ListenerHealthRecord

    /**
     * The listener bound. Sets [ListenerHealthRecord.connected] and stamps
     * `lastConnectedAt`.
     */
    public suspend fun recordConnected(atMillis: Long)

    /**
     * The listener unbound. Clears [ListenerHealthRecord.connected] and stamps
     * `lastDisconnectedAt`.
     *
     * The disk half is best-effort: a disconnect caused by the process being
     * killed may not survive to be written, and nothing in [ListenerHealth]
     * depends on it having been.
     */
    public suspend fun recordDisconnected(atMillis: Long)

    /**
     * The app observed the grant held.
     *
     * Idempotent — the first observation is the one kept, because the value is
     * a *reference point* for measuring staleness, and re-stamping it on every
     * poll would reset the very interval it exists to measure.
     */
    public suspend fun recordGrantObserved(atMillis: Long)
}
