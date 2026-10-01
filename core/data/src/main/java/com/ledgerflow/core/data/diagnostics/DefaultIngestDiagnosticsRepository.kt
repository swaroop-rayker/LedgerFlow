package com.ledgerflow.core.data.diagnostics

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.dao.IngestDiagnosticsDao
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.diagnostics.CaptureOutcomes
import com.ledgerflow.core.domain.diagnostics.DuplicateEvidence
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.IngestDiagnosticsRepository
import com.ledgerflow.core.domain.diagnostics.KeptCopy
import com.ledgerflow.core.domain.diagnostics.NearestRank
import com.ledgerflow.core.domain.diagnostics.PipelineSpeed
import com.ledgerflow.core.domain.diagnostics.ReadQuality
import com.ledgerflow.core.domain.diagnostics.ReadQualityReport
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import com.ledgerflow.core.domain.diagnostics.SenderReadRate
import com.ledgerflow.core.domain.diagnostics.SmsSenderName
import com.ledgerflow.core.domain.diagnostics.SourceLatency
import com.ledgerflow.core.domain.diagnostics.WeakSenders
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.model.RawParseStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The ingest diagnostics report (SPEC.md §13 P5), assembled from
 * [IngestDiagnosticsDao]'s aggregates.
 *
 * **The two capture sources are read by two statements, never by a branch.**
 * `sms_raw` and `notification_raw` are different tables, so capture-to-Inbox
 * time and sender names are one query each, mapped by one function each. There
 * is no `when` on a source type anywhere in this class (CLAUDE.md §0).
 */
@Singleton
public class DefaultIngestDiagnosticsRepository @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : IngestDiagnosticsRepository {

    override suspend fun snapshot(sinceMillis: Long, nowMillis: Long): IngestDiagnostics? =
        withContext(io) {
            // `openForBackgroundWork()` rather than `requireDatabase()`, as the
            // Analytics snapshot does: a report that throws when the vault is
            // shut would crash the screen, where null lets it say so.
            val database = session.openForBackgroundWork() ?: return@withContext null
            val dao = database.ingestDiagnosticsDao()
            val meta = database.appMetaDao()
            val decisions = dao.decisionCount(sinceMillis)

            IngestDiagnostics(
                captured = captured(dao, sinceMillis, nowMillis - CaptureOutcomes.STUCK_AFTER_MILLIS),
                duplicates = DuplicateEvidence(
                    kept = dao.duplicatesByKeptSource(sinceMillis)
                        .map { KeptCopy(source = it.source, count = it.count) }
                        .sortedByDescending { it.count },
                ),
                reading = reading(dao, sinceMillis),
                speed = PipelineSpeed(
                    toInbox = listOfNotNull(
                        latency(
                            IngestSourceType.SMS,
                            count = dao.smsToInboxCount(sinceMillis),
                            at = { offset -> dao.smsToInboxAt(sinceMillis, offset) },
                        ),
                        latency(
                            IngestSourceType.NOTIFICATION,
                            count = dao.notificationToInboxCount(sinceMillis),
                            at = { offset -> dao.notificationToInboxAt(sinceMillis, offset) },
                        ),
                    ),
                    decisions = decisions,
                    decisionMedianMillis = median(decisions) { offset ->
                        dao.decisionAt(sinceMillis, offset)
                    },
                    oldestWaitingMillis = dao.oldestWaitingSince()
                        ?.let { (nowMillis - it).coerceAtLeast(0L) },
                ),
                rollup = RollupHealth(
                    lastReconciledAt = meta.value(AppMetaEntity.KEY_ROLLUP_RECONCILED_AT)
                        ?.toLongOrNull(),
                    bucketsRepaired = meta.value(AppMetaEntity.KEY_ROLLUP_BUCKETS_REPAIRED)
                        ?.toIntOrNull(),
                ),
            )
        }

    private suspend fun captured(
        dao: IngestDiagnosticsDao,
        since: Long,
        stuckBefore: Long,
    ): CaptureOutcomes {
        val counts = dao.outcomes(since)
            .mapNotNull { row -> row.status?.let { it to row.count } }
            .toMap()
        fun count(status: RawParseStatus): Int = counts[status] ?: 0
        return CaptureOutcomes(
            parsed = count(RawParseStatus.PARSED),
            unmatched = count(RawParseStatus.UNMATCHED),
            duplicates = count(RawParseStatus.DUPLICATE_SUPPRESSED),
            failed = count(RawParseStatus.FAILED),
            waiting = count(RawParseStatus.CAPTURED),
            ignored = count(RawParseStatus.SENDER_NOT_ALLOWLISTED),
            stuck = dao.stuck(stuckBefore),
        )
    }

    private suspend fun reading(dao: IngestDiagnosticsDao, since: Long): ReadQualityReport {
        val buckets = dao.confidenceBuckets(since, ReadQuality.LOW_BELOW, ReadQuality.HIGH_FROM)
            .associate { it.bucket to it.count }
        val senders =
            dao.smsSenderReads(since, ReadQuality.LOW_BELOW).map { row ->
                SenderReadRate(SmsSenderName.of(row.sender), weak = row.weak, total = row.total)
            } + dao.notificationSenderReads(since, ReadQuality.LOW_BELOW).map { row ->
                SenderReadRate(row.sender, weak = row.weak, total = row.total)
            }
        return ReadQualityReport(
            notRecognised = buckets[BUCKET_NOT_RECOGNISED] ?: 0,
            low = buckets[BUCKET_LOW] ?: 0,
            medium = buckets[BUCKET_MEDIUM] ?: 0,
            high = buckets[BUCKET_HIGH] ?: 0,
            weakestSenders = WeakSenders.rank(senders),
        )
    }

    /** Null when the source has nothing in the window, so the screen shows no line for it. */
    private suspend fun latency(
        source: IngestSourceType,
        count: Int,
        at: suspend (offset: Int) -> Long?,
    ): SourceLatency? {
        if (count == 0) return null
        val median = at(NearestRank.offset(count, MEDIAN)) ?: return null
        val p90 = at(NearestRank.offset(count, P90)) ?: return null
        return SourceLatency(source, count, medianMillis = median, p90Millis = p90)
    }

    private suspend fun median(count: Int, at: suspend (offset: Int) -> Long?): Long? =
        if (count == 0) null else at(NearestRank.offset(count, MEDIAN))

    private companion object {
        // The buckets `IngestDiagnosticsDao.confidenceBuckets` returns.
        const val BUCKET_NOT_RECOGNISED = 0
        const val BUCKET_LOW = 1
        const val BUCKET_MEDIUM = 2
        const val BUCKET_HIGH = 3

        const val MEDIAN = 50
        const val P90 = 90
    }
}
