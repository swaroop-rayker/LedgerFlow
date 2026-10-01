package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.RawParseStatus

/**
 * The ingest diagnostics screen's reads (SPEC.md §13 P5, DATAVIZ-PLAN C3–C5).
 *
 * **Aggregates only.** Every statement here returns counts, a bucket, one
 * duration or one timestamp, so the screen never loads the message history to
 * count it — the medians come from `ORDER BY … LIMIT 1 OFFSET n`, one row each.
 *
 * **No body, title or amount is selected anywhere in this interface.** The
 * screen names senders (an SMS header, a notification's package) and nothing
 * else a message contained.
 *
 * **It touches neither `ledger_entry` nor `daily_rollup`**, so Law 2's guards
 * have nothing to check here: a candidate belongs to no book until it is
 * approved.
 *
 * `suspend` one-shot reads rather than `Flow`s: this is a report the screen
 * re-reads on resume and on a window change, like the Analytics snapshot, and
 * nine live queries re-running on every captured message would be work nobody
 * is looking at.
 *
 * Capture time is `sms_raw.received_at` / `notification_raw.posted_at` —
 * both stamped from this device's clock at capture — and the window is always
 * applied to it, so every section of the report describes the same messages.
 */
@Dao
public interface IngestDiagnosticsDao {

    /** Raw rows per `parse_status`, both tables, captured at or after [since]. */
    @Query(
        "SELECT parse_status AS status, COUNT(*) AS count FROM (" +
            "SELECT parse_status FROM sms_raw WHERE received_at >= :since " +
            "UNION ALL " +
            "SELECT parse_status FROM notification_raw WHERE posted_at >= :since" +
            ") GROUP BY parse_status",
    )
    public suspend fun outcomes(since: Long): List<StatusCount>

    /** Still `CAPTURED` and captured before [before] — the queue has stopped. No window. */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM sms_raw " +
            "WHERE parse_status = 'CAPTURED' AND received_at < :before) + " +
            "(SELECT COUNT(*) FROM notification_raw " +
            "WHERE parse_status = 'CAPTURED' AND posted_at < :before)",
    )
    public suspend fun stuck(before: Long): Int

    /**
     * Suppressed messages, grouped by the source of the candidate that won.
     *
     * Walks raw row → its candidate → `suppressed_by_id` → the winner. Both
     * joins are `LEFT` so a message whose candidate (or whose winner) has
     * since been erased is still counted, under a null source, and the groups
     * sum to exactly the `DUPLICATE_SUPPRESSED` count [outcomes] reports.
     */
    @Query(
        "SELECT w.source AS source, COUNT(*) AS count FROM (" +
            "SELECT id FROM sms_raw " +
            "WHERE parse_status = 'DUPLICATE_SUPPRESSED' AND received_at >= :since " +
            "UNION ALL " +
            "SELECT id FROM notification_raw " +
            "WHERE parse_status = 'DUPLICATE_SUPPRESSED' AND posted_at >= :since" +
            ") r " +
            "LEFT JOIN pending_transaction p ON p.raw_ref_id = r.id " +
            "LEFT JOIN pending_transaction w ON w.id = p.suppressed_by_id " +
            "GROUP BY w.source",
    )
    public suspend fun duplicatesByKeptSource(since: Long): List<KeptSourceCount>

    /**
     * Candidates made from messages, bucketed by confidence.
     *
     * 0 = not recognised (exactly 0), 1 = below [low], 2 = below [high],
     * 3 = the rest. The thresholds are bound, never written here, so the
     * domain's `ReadQuality` is their one definition. Receipt candidates are
     * excluded by the join: they have no raw message.
     */
    @Query(
        "SELECT CASE " +
            "WHEN p.confidence <= 0 THEN 0 " +
            "WHEN p.confidence < :low THEN 1 " +
            "WHEN p.confidence < :high THEN 2 " +
            "ELSE 3 END AS bucket, COUNT(*) AS count " +
            "FROM pending_transaction p JOIN (" +
            "SELECT id FROM sms_raw WHERE received_at >= :since " +
            "UNION ALL " +
            "SELECT id FROM notification_raw WHERE posted_at >= :since" +
            ") r ON r.id = p.raw_ref_id " +
            "GROUP BY bucket",
    )
    public suspend fun confidenceBuckets(since: Long, low: Double, high: Double): List<BucketCount>

    /** Per SMS sender header: candidates, and how many read below [low]. */
    @Query(
        "SELECT s.sender AS sender, COUNT(*) AS total, " +
            "SUM(CASE WHEN p.confidence < :low THEN 1 ELSE 0 END) AS weak " +
            "FROM sms_raw s JOIN pending_transaction p ON p.raw_ref_id = s.id " +
            "WHERE s.received_at >= :since GROUP BY s.sender",
    )
    public suspend fun smsSenderReads(since: Long, low: Double): List<SenderReads>

    /**
     * Per notifying app: candidates, and how many read below [low].
     *
     * Named by the allowlist's curated label ("Google Pay"), falling back to
     * the package name for an app the curated list does not label. Read here
     * rather than from `PackageManager`, which since Android 11 cannot see
     * another app's label without a `<queries>` entry this app does not have.
     */
    @Query(
        "SELECT COALESCE(a.label, n.package_name) AS sender, COUNT(*) AS total, " +
            "SUM(CASE WHEN p.confidence < :low THEN 1 ELSE 0 END) AS weak " +
            "FROM notification_raw n JOIN pending_transaction p ON p.raw_ref_id = n.id " +
            "LEFT JOIN package_allowlist a ON a.package_name = n.package_name " +
            "WHERE n.posted_at >= :since GROUP BY n.package_name",
    )
    public suspend fun notificationSenderReads(since: Long, low: Double): List<SenderReads>

    // ── Capture → Inbox, one pair per raw table ─────────────────────────────
    //
    // A count, then one delay at a nearest-rank offset. Negative differences
    // cannot arise from one clock unless it was set back between capture and
    // parse; they are clamped to 0 rather than reported as time travel.

    @Query(
        "SELECT COUNT(*) FROM sms_raw s JOIN pending_transaction p ON p.raw_ref_id = s.id " +
            "WHERE s.received_at >= :since",
    )
    public suspend fun smsToInboxCount(since: Long): Int

    @Query(
        "SELECT MAX(p.created_at - s.received_at, 0) AS delay " +
            "FROM sms_raw s JOIN pending_transaction p ON p.raw_ref_id = s.id " +
            "WHERE s.received_at >= :since ORDER BY delay LIMIT 1 OFFSET :offset",
    )
    public suspend fun smsToInboxAt(since: Long, offset: Int): Long?

    @Query(
        "SELECT COUNT(*) FROM notification_raw n " +
            "JOIN pending_transaction p ON p.raw_ref_id = n.id WHERE n.posted_at >= :since",
    )
    public suspend fun notificationToInboxCount(since: Long): Int

    @Query(
        "SELECT MAX(p.created_at - n.posted_at, 0) AS delay " +
            "FROM notification_raw n JOIN pending_transaction p ON p.raw_ref_id = n.id " +
            "WHERE n.posted_at >= :since ORDER BY delay LIMIT 1 OFFSET :offset",
    )
    public suspend fun notificationToInboxAt(since: Long, offset: Int): Long?

    // ── Inbox → the user's decision ─────────────────────────────────────────
    //
    // Windowed on the candidate's `created_at`: every candidate, messages and
    // receipts alike, is a decision the user made.

    @Query(
        "SELECT COUNT(*) FROM pending_transaction " +
            "WHERE status IN ('APPROVED', 'DISCARDED') AND reviewed_at IS NOT NULL " +
            "AND created_at >= :since",
    )
    public suspend fun decisionCount(since: Long): Int

    @Query(
        "SELECT MAX(reviewed_at - created_at, 0) AS delay FROM pending_transaction " +
            "WHERE status IN ('APPROVED', 'DISCARDED') AND reviewed_at IS NOT NULL " +
            "AND created_at >= :since ORDER BY delay LIMIT 1 OFFSET :offset",
    )
    public suspend fun decisionAt(since: Long, offset: Int): Long?

    /**
     * The oldest candidate still waiting for the user. The same predicate as
     * `PendingTransactionDao.observePending`, so it describes the queue the
     * Inbox shows. No window: waiting is a present condition.
     */
    @Query(
        "SELECT MIN(created_at) FROM pending_transaction " +
            "WHERE status = 'PENDING' AND suppressed_by_id IS NULL",
    )
    public suspend fun oldestWaitingSince(): Long?
}

/** One `parse_status` and how many raw rows carry it. */
public data class StatusCount(
    @ColumnInfo(name = "status") val status: RawParseStatus?,
    @ColumnInfo(name = "count") val count: Int,
)

/** Suppressed messages whose kept copy came from [source]; null = no longer on record. */
public data class KeptSourceCount(
    @ColumnInfo(name = "source") val source: EntrySource?,
    @ColumnInfo(name = "count") val count: Int,
)

/** One confidence bucket (see [IngestDiagnosticsDao.confidenceBuckets]). */
public data class BucketCount(
    @ColumnInfo(name = "bucket") val bucket: Int,
    @ColumnInfo(name = "count") val count: Int,
)

/** One sender's candidates. */
public data class SenderReads(
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "weak") val weak: Int,
)
