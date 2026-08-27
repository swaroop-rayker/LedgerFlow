package com.ledgerflow.core.testing.ingest

import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.CapturedEvent
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.DuplicateMatcher
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ingest.RawIngestRepository

/**
 * An in-memory [RawIngestRepository].
 *
 * A fake rather than a mock, per CLAUDE.md §12's preference: the interesting
 * behaviours here are stateful — a second identical event is a duplicate, a
 * seeded allowlist changes what the next call answers — and a mock would only
 * ever assert that a method was called.
 *
 * Deliberately keeps what it was given. Tests about the privacy rule need to be
 * able to assert that a body was **never** recorded, and that is a statement
 * about [recorded] being empty rather than about a call not happening.
 */
public class FakeRawIngestRepository(
    private val allowedPackages: MutableSet<String> = mutableSetOf(),
    private val allowedSenders: MutableSet<String> = mutableSetOf(),
) : RawIngestRepository {

    /** Every event that reached the repository, in order. */
    public val recorded: MutableList<RawIngestEvent> = mutableListOf()

    public var seedCount: Int = 0
        private set

    public var purgedBodies: Int = 0

    public var ruleSeedCount: Int = 0
        private set

    /** Rules [parserRules] hands back. Empty unless a test sets them. */
    public var rules: List<ParserRule> = emptyList()

    /** Every outcome recorded, as (rawId, ruleId, matched). */
    public val parseOutcomes: MutableList<Triple<String, String?, Boolean>> = mutableListOf()

    /**
     * Every candidate written, keyed by the raw row that produced it.
     *
     * A map rather than a list because P2-4's property is exactly "one raw row,
     * at most one candidate" — a list would let a test pass while holding two
     * entries for the same `rawId`, which is the bug.
     */
    public val pending: MutableMap<String, PendingCandidate> = mutableMapOf()

    /**
     * Which candidates lost a dedupe, keyed by raw id, valued by the winner's
     * pending id — the fake's `suppressed_by_id`.
     *
     * A suppressed candidate stays in [pending]: §3.1 retains the row and shows
     * it under the Inbox's "Suppressed" filter, so a fake that removed it would
     * let a test assert "one pending row" about a pipeline that had actually
     * dropped one.
     */
    public val suppressedBy: MutableMap<String, String> = mutableMapOf()

    /** Candidates that won, or were never contested. What the Inbox lists. */
    public val liveCandidates: Map<String, PendingCandidate>
        get() = pending.filterKeys { it !in suppressedBy }

    /** Set to make the next [recordParseOutcome] fail, as a locked vault would. */
    public var failPendingWrites: Boolean = false

    private val seenHashes = mutableSetOf<String>()

    public fun allowPackage(packageName: String) {
        allowedPackages += packageName
    }

    public fun allowSender(sender: String) {
        allowedSenders += sender
    }

    override suspend fun isPackageAllowed(packageName: String): Boolean =
        packageName in allowedPackages

    override suspend fun isSenderAllowed(sender: String): Boolean =
        sender.uppercase() in allowedSenders.map { it.uppercase() }

    override suspend fun record(event: RawIngestEvent): CaptureOutcome {
        recorded += event
        // Stands in for the unique body_hash: same origin and body inside the
        // same minute is a re-delivery.
        val bucket = event.receivedAt / MILLIS_PER_MINUTE
        val key = "${event.packageName ?: event.sender}|${event.body}|$bucket"
        return if (!seenHashes.add(key)) {
            CaptureOutcome.AlreadySeen
        } else {
            CaptureOutcome.Recorded("raw-${recorded.size}")
        }
    }

    override suspend fun capturedEvents(limit: Int): List<CapturedEvent> =
        recorded.take(limit).mapIndexed { index, event -> CapturedEvent("raw-${index + 1}", event) }

    override suspend fun triageCapturedSms(limit: Int): Int =
        recorded.count { !isSenderAllowed(it.sender) }

    /**
     * Rows the fake will report as re-admitted, and whether it has already done
     * so — the fingerprint gate, modelled rather than reimplemented.
     */
    public var readmitOnNextTriage: Int = 0

    override suspend fun retriageRejectedSms(limit: Int): Int {
        val readmitted = minOf(readmitOnNextTriage, limit)
        // Once, like the real one: the marker advances and a second pass over an
        // unchanged allowlist finds nothing. A fake that re-admitted on every
        // call would let a test assert progress that production does not make.
        readmitOnNextTriage = 0
        return readmitted
    }

    override suspend fun purgeExpiredBodies(): Int = purgedBodies

    override suspend fun seedAllowlists() {
        seedCount++
    }

    override suspend fun seedParserRules() {
        ruleSeedCount++
    }

    override suspend fun parserRules(): List<ParserRule> = rules

    /**
     * Both writes, or neither — the fake keeps the real one's atomicity.
     *
     * A fake that recorded the verdict and skipped the candidate on failure
     * would make the pipeline look idempotent for a reason the database does not
     * actually provide, which is the kind of agreement between test double and
     * test that the corpus lesson is about.
     */
    override suspend fun recordParseOutcome(
        rawId: String,
        ruleId: String?,
        candidate: PendingCandidate,
    ): PendingWriteOutcome {
        if (failPendingWrites) return PendingWriteOutcome.Failed("fake refused")

        pending[rawId]?.let { return PendingWriteOutcome.AlreadyPending("pending-$rawId") }

        parseOutcomes += Triple(rawId, ruleId, ruleId != null)

        // §3.1's dedupe, through the same DuplicateMatcher production uses.
        // Reimplementing the rule here is the trap: the fake would then agree
        // with whatever the test author believed rather than with the code, and
        // a pipeline test could pass against a rule that does not ship.
        //
        // No window, because there is no clock -- everything the fake holds is
        // "recent". That makes it stricter than production, never looser, so a
        // dedupe the fake reports is a dedupe the database would also make.
        val winner = pending.entries
            .filter { (otherRaw, other) ->
                otherRaw != rawId &&
                    suppressedBy[otherRaw] == null &&
                    !DedupeKey.isUnkeyed(candidate.dedupeKey) &&
                    other.dedupeKey == candidate.dedupeKey &&
                    DuplicateMatcher.isSameTransaction(candidate.extracted, other.extracted)
            }
            .maxByOrNull { it.value.confidence }

        pending[rawId] = candidate

        return when {
            winner == null -> PendingWriteOutcome.Created("pending-$rawId")

            winner.value.confidence >= candidate.confidence -> {
                suppressedBy[rawId] = "pending-${winner.key}"
                PendingWriteOutcome.Suppressed("pending-$rawId", "pending-${winner.key}")
            }

            else -> {
                suppressedBy[winner.key] = "pending-$rawId"
                PendingWriteOutcome.Created(
                    pendingId = "pending-$rawId",
                    supersededPendingId = "pending-${winner.key}",
                )
            }
        }
    }

    private companion object {
        /** §5.1's capture-time dedupe bucket. */
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
