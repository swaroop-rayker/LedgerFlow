package com.ledgerflow.core.domain.ingest

/**
 * Asks for a parse pass over whatever the pipeline has not resolved yet
 * (SPEC.md §5.1, §5.2).
 *
 * **A port, because capture is not the only thing that creates work.** The
 * obvious caller is a capture adapter — a message arrived, look at it — and for
 * three steps that was the only one, so enqueueing lived inside the sink. §16
 * Q14 added a second: re-triage is triggered by the *allowlist* changing, which
 * happens when the app launches onto a new shipped seed, or at P5 when a user
 * adds their bank in Settings. Neither is a message arriving. Left as it was,
 * the fix for a wrongly-rejected message sat unrun until the user happened to
 * receive another SMS — found on the owner's device, where it did.
 *
 * It lives in `:core:domain` rather than beside the worker for the reason every
 * port here does: `:app` and any future Settings screen have to be able to ask,
 * features may not depend on features (CLAUDE.md §3), and what they need is a
 * promise — "look again when convenient" — rather than a `WorkManager`.
 *
 * **Asking is always safe.** The pass is idempotent: a raw row that already
 * produced a candidate produces no second one, and the implementation collapses
 * concurrent requests into a single run. The cost of an unnecessary call is a
 * query that finds nothing.
 *
 * A `fun interface` so a test can pass a lambda. That is not a courtesy — the
 * only implementation needs a real `WorkManager`, which no JVM unit test has,
 * and `AppViewModel` is unit-tested.
 */
public fun interface IngestWorkTrigger {

    /** Requests a pass. Returns immediately; the work happens off the caller's thread. */
    public fun requestParsePass()
}
