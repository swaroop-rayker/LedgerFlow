package com.ledgerflow.core.domain.ingest

/** What became of one captured event on its way into the raw tables. */
public sealed interface CaptureOutcome {

    /** Persisted. [rawId] is the `sms_raw` / `notification_raw` row. */
    public data class Recorded(val rawId: String) : CaptureOutcome

    /**
     * The same message was already captured.
     *
     * Not a failure. The network re-delivers SMS, and a notification can be
     * re-posted unchanged; `body_hash` is unique precisely so the second arrival
     * is absorbed by the database rather than by a caller remembering to check.
     */
    public data object AlreadySeen : CaptureOutcome

    /**
     * Nothing was written, and nothing was read.
     *
     * The only current cause is a notification whose package is not allowlisted
     * (§5.2). Distinct from a failure: refusing to read is the feature.
     */
    public data object NotAllowed : CaptureOutcome

    /** The write itself failed. The message is lost; say so rather than pretend. */
    public data class Failed(val reason: String) : CaptureOutcome
}

/**
 * The raw capture tables and the two allowlists that gate them
 * (SPEC.md §5.1, §5.2).
 *
 * A port in `:core:domain` rather than a DAO reached directly, for the usual
 * reason and one specific one: the capture adapters live in `:feature:ingest`,
 * features may not see `:core:data` (CLAUDE.md §3), and the thing they need is
 * a promise about behaviour — "persist this verbatim and tell me if it is a
 * duplicate" — rather than a table.
 *
 * **[isPackageAllowed] is not a convenience.** §5.2's privacy rule is that the
 * filter runs before any body access, so this is the call a notification
 * listener makes *first*, on nothing but a package name, and the notification's
 * contents are never touched unless it returns true.
 */
public interface RawIngestRepository {

    /**
     * May LedgerFlow read notifications from this package at all? (D-10.)
     *
     * Takes a package name and nothing else, deliberately: a signature that
     * accepted the notification would invite a caller to read it first.
     */
    public suspend fun isPackageAllowed(packageName: String): Boolean

    /** Is this SMS sender a financial one? (§5.1, applied after capture.) */
    public suspend fun isSenderAllowed(sender: String): Boolean

    /**
     * Persists one captured event verbatim, before anything has parsed it.
     *
     * Must return fast and must not throw: a capture adapter has ~10 seconds and
     * no recovery (CLAUDE.md §7).
     */
    public suspend fun record(event: RawIngestEvent): CaptureOutcome

    /**
     * Applies the sender allowlist to SMS captured but not yet resolved,
     * returning how many were marked as not financial.
     *
     * SMS is written first and filtered here because the receiver cannot safely
     * do lookups inside its ten seconds (§5.1). Notifications need no equivalent
     * — theirs is filtered before the row exists.
     */
    public suspend fun triageCapturedSms(limit: Int): Int

    /**
     * D-09: blanks raw bodies past their retention, keeping the rows.
     *
     * Returns how many bodies were cleared. The parse result and any
     * `pending_transaction` derived from one survive — retention drops the
     * text, never the history.
     */
    public suspend fun purgeExpiredBodies(): Int

    /** Seeds the curated allowlists, leaving anything the user has changed alone (D-10). */
    public suspend fun seedAllowlists()
}
