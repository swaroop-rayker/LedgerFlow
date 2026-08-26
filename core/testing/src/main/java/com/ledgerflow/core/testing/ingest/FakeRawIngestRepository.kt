package com.ledgerflow.core.testing.ingest

import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.RawIngestEvent
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

    override suspend fun triageCapturedSms(limit: Int): Int =
        recorded.count { !isSenderAllowed(it.sender) }

    override suspend fun purgeExpiredBodies(): Int = purgedBodies

    override suspend fun seedAllowlists() {
        seedCount++
    }

    private companion object {
        /** §5.1's capture-time dedupe bucket. */
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
