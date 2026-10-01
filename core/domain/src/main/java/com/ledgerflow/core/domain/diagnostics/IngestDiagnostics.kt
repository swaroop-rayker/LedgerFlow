package com.ledgerflow.core.domain.diagnostics

import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.model.EntrySource

/**
 * The ingest diagnostics screen's report (SPEC.md §13 P5, `docs/DATAVIZ-PLAN.md`
 * C3–C5).
 *
 * **Counts and durations only — never money.** Every figure here is a number of
 * messages or a number of milliseconds, so no field can combine the two books
 * (Law 2) and none is a monetary amount (Law 3). `DiagnosticsCarryNoMoneyTest`
 * holds that by reflection rather than by review.
 *
 * **Never a message body.** Senders are named by their SMS header or their
 * app's label, the same names the Inbox already shows, and SMS from senders
 * that are not on the allowlist appear only as a count.
 */
public data class IngestDiagnostics(
    val captured: CaptureOutcomes,
    val duplicates: DuplicateEvidence,
    val reading: ReadQualityReport,
    val speed: PipelineSpeed,
    val rollup: RollupHealth,
)

/** The report's time window, chosen on the screen. */
public enum class DiagnosticsWindow(
    /** Days back from now, or null for everything on record. */
    public val days: Int?,
) {
    LAST_30_DAYS(30),
    LAST_90_DAYS(90),
    ALL_TIME(null),
    ;

    /** The earliest capture time inside the window, epoch millis. */
    public fun sinceMillis(nowMillis: Long): Long =
        days?.let { nowMillis - it * DAY_MILLIS } ?: 0L

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/**
 * What happened to each captured message (`parse_status` on `sms_raw` and
 * `notification_raw`).
 *
 * Read from the raw tables rather than from `pending_transaction`, because a
 * raw row outlives both its body (D-09) and its candidate (the Inbox's erase),
 * so it is the durable record of what the pipeline did.
 */
public data class CaptureOutcomes(
    /** A rule read it. */
    val parsed: Int,
    /** No rule matched; it is in the Inbox anyway, at confidence 0 (§5.1). */
    val unmatched: Int,
    /** The same payment had already arrived another way (§3.1). */
    val duplicates: Int,
    /** The worker recorded an error on it. */
    val failed: Int,
    /** Captured inside the window and not read yet — normally seconds. */
    val waiting: Int,
    /**
     * SMS from senders not on the allowlist: looked at, found not to be from a
     * bank, and left alone. Counted, never named. Zero on `playSafe`.
     */
    val ignored: Int,
    /**
     * Messages still unread [STUCK_AFTER_MILLIS] after capture, **whatever the
     * window** — a queue that has stopped is a present condition, and a
     * 30-day window would hide one that stopped 31 days ago.
     */
    val stuck: Int,
) {
    /** Everything from a bank or payment app: all but [ignored]. */
    public val fromFinancialSenders: Int
        get() = parsed + unmatched + duplicates + failed + waiting

    public companion object {
        /** A message the worker has not read after this long is stuck. */
        public const val STUCK_AFTER_MILLIS: Long = 10L * 60L * 1000L
    }
}

/**
 * C3 — messages that were the same payment arriving twice, partitioned by
 * which copy was kept.
 *
 * The partition is exact: each suppressed message is counted under the source
 * of the candidate that won, or under `null` when that candidate is no longer
 * on record (the user erased it from the Inbox). So [total] always equals
 * [CaptureOutcomes.duplicates] for the same window.
 */
public data class DuplicateEvidence(
    val kept: List<KeptCopy>,
) {
    public val total: Int get() = kept.sumOf { it.count }

    /** Duplicates whose candidates are still in the Inbox's Suppressed filter. */
    public val stillInInbox: Int get() = kept.filter { it.source != null }.sumOf { it.count }
}

/** How many duplicates were set aside in favour of a copy from [source]. */
public data class KeptCopy(
    /** Null: the kept candidate is no longer on record. */
    val source: EntrySource?,
    val count: Int,
)

/**
 * C4 — how confidently the rules read the messages they were given.
 *
 * Confidence is a real number in 0..1 and legitimately so (Law 3 bans floating
 * point for money, not for this). The buckets are [ReadQuality]'s.
 */
public data class ReadQualityReport(
    val notRecognised: Int,
    val low: Int,
    val medium: Int,
    val high: Int,
    /** Senders with the most weakly read messages, worst first; at most [WeakSenders.LIMIT]. */
    val weakestSenders: List<SenderReadRate>,
) {
    public val total: Int get() = notRecognised + low + medium + high
}

/**
 * The confidence buckets.
 *
 * Owner-approved thresholds (2026-09-30). The SQL binds these constants rather
 * than repeating them, so the query and this definition cannot disagree.
 */
public enum class ReadQuality {
    /** Confidence 0: no rule matched. */
    NOT_RECOGNISED,

    /** Above 0 and below [LOW_BELOW]. */
    LOW,

    /** From [LOW_BELOW] and below [HIGH_FROM]. */
    MEDIUM,

    /** [HIGH_FROM] and above. */
    HIGH,
    ;

    public companion object {
        public const val LOW_BELOW: Double = 0.5
        public const val HIGH_FROM: Double = 0.8
    }
}

/** One sender's messages, and how many of them were read weakly (below [ReadQuality.LOW_BELOW]). */
public data class SenderReadRate(
    /** An SMS header's entity (`HDFCBK`) or an app's label. */
    val sender: String,
    val weak: Int,
    val total: Int,
)

/**
 * Which senders the parser reads worst.
 *
 * This is the list that turns into parser rules and corpus fixtures
 * (CLAUDE.md §11), so it is **ranked by how many messages were read weakly**,
 * the same frequency argument `ParserGapDetection` makes: the value of a new
 * rule is the messages it would fix. The weak share breaks ties.
 */
public object WeakSenders {

    public const val LIMIT: Int = 5

    public fun rank(rows: List<SenderReadRate>): List<SenderReadRate> = rows
        .groupBy { it.sender }
        .map { (sender, group) ->
            SenderReadRate(sender, weak = group.sumOf { it.weak }, total = group.sumOf { it.total })
        }
        .filter { it.weak > 0 }
        .sortedWith(
            compareByDescending<SenderReadRate> { it.weak }
                // weak/total, compared as a cross-multiplication so no
                // division (and no rounding) decides an order.
                .thenComparator { a, b -> (b.weak * a.total).compareTo(a.weak * b.total) }
                .thenBy { it.sender },
        )
        .take(LIMIT)
}

/**
 * An SMS sender header, reduced to the entity that sent it.
 *
 * A TRAI DLT header is `XX-ENTITY-C`: a rotating two-letter operator prefix,
 * the entity, and a route class (`T`, `S`, `P`, `G`). `VM-HDFCBK-S` and
 * `AD-HDFCBK-T` are one bank, and ranking them as two would halve the bank's
 * weight in [WeakSenders.rank]. Anything not in that shape is kept as it is.
 */
public object SmsSenderName {

    private val DLT = Regex("^[A-Z0-9]{2}-([A-Z0-9]+)(?:-[A-Z])?$")

    public fun of(header: String): String {
        val upper = header.trim().uppercase()
        return DLT.matchEntire(upper)?.groupValues?.get(1) ?: header.trim()
    }
}

/** C5 — how long messages take to reach the Inbox, and the user to decide. */
public data class PipelineSpeed(
    /** Capture → Inbox, one entry per capture source that has any. */
    val toInbox: List<SourceLatency>,
    /** Inbox → approved or discarded, in the window. */
    val decisions: Int,
    val decisionMedianMillis: Long?,
    /** How long the oldest candidate still waiting for the user has waited, or null. */
    val oldestWaitingMillis: Long?,
)

/**
 * Capture → Inbox for one source.
 *
 * Both ends are this device's clock (the receiver's and the listener's
 * `clock.nowMillis()`, and the candidate writer's), so the difference is the
 * pipeline's own time and not a bank's timestamp.
 */
public data class SourceLatency(
    val source: IngestSourceType,
    val count: Int,
    val medianMillis: Long,
    /** The slowest tenth start here. */
    val p90Millis: Long,
)

/**
 * The rollup's self-repair record (ADR-0006): when reconciliation last ran and
 * how many buckets it had to fix. Non-zero on a healthy install is a bug.
 */
public data class RollupHealth(
    val lastReconciledAt: Long?,
    val bucketsRepaired: Int?,
)

/**
 * The nearest-rank percentile, as a zero-based `OFFSET` into an ascending list.
 *
 * Nearest-rank rather than interpolated so the answer is always a value that
 * actually occurred — a median of two latencies should be one of them, not a
 * duration no message took — and so the query can fetch exactly one row.
 */
public object NearestRank {

    public fun offset(count: Int, percent: Int): Int {
        require(count > 0) { "No values to rank" }
        require(percent in 1..PERCENT) { "Percent must be 1..$PERCENT, was $percent" }
        // ceil(count * percent / 100), in integers.
        val rank = (count.toLong() * percent + PERCENT - 1) / PERCENT
        return (rank - 1).toInt()
    }

    private const val PERCENT = 100
}

/** The report's port. Implemented in `:core:data`. */
public interface IngestDiagnosticsRepository {

    /**
     * The report for messages captured at or after [sinceMillis], as of
     * [nowMillis] — the one clock reading that "stuck" and "waiting for" are
     * measured from. Null when the vault cannot be opened.
     */
    public suspend fun snapshot(sinceMillis: Long, nowMillis: Long): IngestDiagnostics?
}
