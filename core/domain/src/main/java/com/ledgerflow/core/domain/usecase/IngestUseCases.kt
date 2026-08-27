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
 * The worker's pass over raw rows, before the engine sees them (SPEC.md §5.1).
 *
 * Clears bodies past their retention (D-09), reconsiders SMS the allowlist
 * previously rejected (§16 Q14), and applies the allowlist to what is newly
 * captured. Parsing follows in the same worker run.
 *
 * **The order is the contract.**
 *
 * 1. Purge first, so a body that expired this minute is not re-admitted a step
 *    later with nothing left behind it to parse.
 * 2. Re-triage second, so anything the allowlist has newly come to accept is
 *    back at `CAPTURED` *before* the engine runs and is parsed in this same
 *    pass rather than waiting for the next message to arrive.
 * 3. Triage last, over everything now at `CAPTURED`. A row re-admitted in
 *    step 2 passes it by construction — it was re-admitted because the sender
 *    is allowed — so the redundant re-check costs a lookup and buys the
 *    property that exactly one place decides what "allowlisted" means.
 */
public class TriageCapturedIngestUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): TriageReport {
        val purged = repository.purgeExpiredBodies()
        val readmitted = repository.retriageRejectedSms(limit)
        val filtered = repository.triageCapturedSms(limit)
        return TriageReport(
            sendersFiltered = filtered,
            bodiesPurged = purged,
            sendersReadmitted = readmitted,
        )
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

/**
 * What one triage pass did. Reported so a run that does nothing is
 * distinguishable from one that failed.
 *
 * [sendersReadmitted] is §16 Q14's re-triage: messages the allowlist once
 * rejected and now accepts. It is normally 0 and is briefly non-zero after the
 * allowlist changes, which is exactly when someone reading a log wants to know.
 */
public data class TriageReport(
    val sendersFiltered: Int,
    val bodiesPurged: Int,
    val sendersReadmitted: Int = 0,
)

/**
 * Puts the shipped ruleset in place (SPEC.md §5.1).
 *
 * Runs alongside the allowlist seeding, on unlock, for the same reason: the
 * rules live in the vault, so there is nothing to write to until it opens.
 * Idempotent, and it never disturbs a rule the user wrote.
 */
public class SeedParserRulesUseCase @Inject constructor(
    private val repository: RawIngestRepository,
) {
    public suspend operator fun invoke(): Unit = repository.seedParserRules()
}

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
