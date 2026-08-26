package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import javax.inject.Inject

/**
 * May LedgerFlow read this package's notifications? (SPEC.md §5.2, D-10.)
 *
 * **The first call `NotificationIngestService` makes, and the gate on every
 * other one.** §5.2 and CLAUDE.md §7 both state the same rule: the allowlist
 * runs before any notification body is read, and content from a package outside
 * it is never read, logged or persisted. Taking only a package name is what
 * makes that checkable at the call site — there is no notification in this
 * signature to be tempted by.
 */
public class IsPackageAllowedForIngestUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(packageName: String): Boolean =
        repository.isPackageAllowed(packageName)
}

/**
 * Persists one captured message verbatim (SPEC.md §5.1, §5.2).
 *
 * Everything a capture adapter does ends here. It performs no parsing, no
 * joins and no allowlist decision for SMS — the receiver has ~10 seconds
 * (CLAUDE.md §7), and §5.1's guarantee that a financial SMS is never silently
 * dropped only holds if the raw row is written before anything is judged.
 */
public class RecordCapturedEventUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(event: RawIngestEvent): CaptureOutcome =
        repository.record(event)
}

/**
 * The worker's pass over captured messages (SPEC.md §5.1).
 *
 * At P2 this applies the SMS sender allowlist and clears bodies past their
 * retention (D-09). Parsing joins it at the next step — the ruleset does not
 * exist yet, and a row it cannot judge stays `CAPTURED` rather than being given
 * a verdict nothing produced.
 */
public class TriageCapturedIngestUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): TriageReport {
        val purged = repository.purgeExpiredBodies()
        val filtered = repository.triageCapturedSms(limit)
        return TriageReport(sendersFiltered = filtered, bodiesPurged = purged)
    }

    private companion object {
        /**
         * A bound rather than "everything".
         *
         * A worker that wakes to a backlog should make progress and finish, not
         * hold a transaction open across thousands of rows on a device that may
         * kill it. WorkManager re-runs it.
         */
        const val DEFAULT_LIMIT = 200
    }
}

/** What one triage pass did. Reported so a run that does nothing is distinguishable from one that failed. */
public data class TriageReport(val sendersFiltered: Int, val bodiesPurged: Int)

/**
 * Puts the curated allowlists in place on first run (D-10).
 *
 * Idempotent, and deliberately additive: a package the user disabled stays
 * disabled across app updates, because a "curated default" that silently
 * re-enables what someone turned off is not a default, it is an override.
 */
public class SeedIngestAllowlistsUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(): Unit = repository.seedAllowlists()
}
