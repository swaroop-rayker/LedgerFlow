package com.ledgerflow.core.domain.ingest

import kotlinx.coroutines.flow.Flow

/**
 * A stretch of time in which background capture could not reach the vault
 * (SPEC.md §16 Q16, owner's design 2026-10-12).
 *
 * A message that arrives while the vault cannot open has nowhere to be written,
 * and until this existed the only trace was a log line, which on a user's phone
 * is a silent drop -- the thing §5.1 says never happens to a financial SMS.
 * This record is how it stops being silent: Home says "between these times,
 * messages could not be recorded", and the user checks their bank app for that
 * window.
 *
 * **When, and how many: never what.** No sender, no package, no text, no amount.
 * [missed] counts every capture that failed, personal messages included,
 * because the vault being shut is exactly when nothing can tell them apart --
 * which is why the banner shows the window and not the count (the count would
 * overstate missed payments).
 */
public data class CaptureGap(
    val firstMissedAt: Long,
    val lastMissedAt: Long,
    val missed: Int,
)

/**
 * Where [CaptureGap] is kept: **not the vault**, which is by definition the
 * thing that could not be opened when this is written. The out-of-vault store
 * of ADR-0020, whose rule it fits -- operational metadata about the app's own
 * machinery, nothing derived from a message's content.
 *
 * A port in `:core:domain` for the same reason as [ListenerHealthStore]:
 * `:core:data` writes it, `:feature:dashboard` reads it, and features never
 * depend on features.
 */
public interface CaptureGapStore {

    /** The open gap, or null when there is none to report. */
    public val gap: Flow<CaptureGap?>

    /** One capture failed at [atMillis]: widens the window and counts it. */
    public suspend fun recordMissed(atMillis: Long)

    /** The user has seen it ("Got it"). The next failure starts a new window. */
    public suspend fun clear()
}
