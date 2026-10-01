package com.ledgerflow.core.data.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.domain.diagnostics.DiagnosticsWindow
import com.ledgerflow.core.domain.diagnostics.KeptCopy
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import com.ledgerflow.core.domain.diagnostics.SenderReadRate
import com.ledgerflow.core.domain.diagnostics.SourceLatency
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostics report's SQL, against a real encrypted database (P5).
 *
 * Instrumented because every figure is a SQLite aggregate — `UNION ALL`, two
 * `LEFT JOIN`s, `ORDER BY … OFFSET` — and a JVM fake would only restate what
 * we believe those do. One population covers every outcome, both sources, a
 * duplicate whose kept copy has been erased, a personal SMS, a stuck message
 * and a message outside the window, so each assertion is also a check that the
 * others' rows did not leak into it.
 */
@RunWith(AndroidJUnit4::class)
class IngestDiagnosticsRepositoryTest {

    private val vault = LedgerTestVault("lf_diagnostics_test")
    private lateinit var repository: DefaultIngestDiagnosticsRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        vault.now = NOW
        repository = DefaultIngestDiagnosticsRepository(vault.session, Dispatchers.IO)
        seed()
    }

    @After
    fun tearDown() = vault.close()

    private val db get() = vault.session.requireDatabase()

    private suspend fun sms(id: String, sender: String, at: Long, status: RawParseStatus) =
        db.smsRawDao().insert(
            SmsRawEntity(
                id = id,
                sender = sender,
                body = "body $id",
                bodyHash = "hash-$id",
                receivedAt = at,
                simSlot = null,
                parseStatus = status,
                matchedRuleId = null,
                retentionExpiresAt = at + RETENTION,
            ),
        )

    private suspend fun notification(id: String, pkg: String, at: Long, status: RawParseStatus) =
        db.notificationRawDao().insert(
            NotificationRawEntity(
                id = id,
                packageName = pkg,
                title = null,
                body = "body $id",
                bodyHash = "hash-$id",
                postedAt = at,
                parseStatus = status,
                matchedRuleId = null,
                retentionExpiresAt = at + RETENTION,
            ),
        )

    @Suppress("LongParameterList")
    private suspend fun candidate(
        id: String,
        source: EntrySource,
        rawRefId: String?,
        confidence: Double,
        createdAt: Long,
        status: PendingStatus = PendingStatus.PENDING,
        reviewedAt: Long? = null,
        suppressedById: String? = null,
    ) = db.pendingTransactionDao().insert(
        PendingTransactionEntity(
            id = id,
            source = source,
            dedupeKey = "key-$id",
            suppressedById = suppressedById,
            rawRefId = rawRefId,
            extractedJson = "{}",
            confidence = confidence,
            status = status,
            needsManualFill = confidence == 0.0,
            createdAt = createdAt,
            reviewedAt = reviewedAt,
            approvedEntryId = null,
        ),
    )

    private suspend fun seed() {
        db.packageAllowlistDao().upsert(listOf(PackageAllowlistEntity(GPAY, "Google Pay", enabled = true)))

        // Two headers of one bank, both read; one read well, one not at all.
        sms("s1", "VM-HDFCBK-S", NOW - DAY, RawParseStatus.PARSED)
        candidate("p1", EntrySource.SMS, "s1", 0.9, NOW - DAY + 1_000L, PendingStatus.APPROVED, NOW - DAY + 1_000L + HOUR)
        sms("s2", "AD-HDFCBK-T", NOW - 2 * DAY, RawParseStatus.UNMATCHED)
        candidate("p2", EntrySource.SMS, "s2", 0.0, NOW - 2 * DAY + 3_000L)

        // A notification read weakly, and its duplicate, kept in favour of p1.
        notification("n1", GPAY, NOW - DAY, RawParseStatus.PARSED)
        candidate("p3", EntrySource.NOTIFICATION, "n1", 0.4, NOW - DAY + 500L)
        notification("n2", GPAY, NOW - DAY, RawParseStatus.DUPLICATE_SUPPRESSED)
        candidate("p4", EntrySource.NOTIFICATION, "n2", 0.3, NOW - DAY + 200L, suppressedById = "p1")

        // A duplicate whose candidate the user has since erased.
        sms("s3", "VM-SBIUPI-S", NOW - 3 * DAY, RawParseStatus.DUPLICATE_SUPPRESSED)

        // A personal SMS: counted, never joined to anything.
        sms("s4", "AX-FRIEND", NOW - DAY, RawParseStatus.SENDER_NOT_ALLOWLISTED)

        // Unread: one for twenty minutes (stuck), one for one minute (not).
        sms("s5", "VM-HDFCBK-S", NOW - 20 * MINUTE, RawParseStatus.CAPTURED)
        notification("n3", GPAY, NOW - MINUTE, RawParseStatus.CAPTURED)

        // Forty days old: outside the 30-day window, inside "all time".
        sms("s6", "VM-HDFCBK-S", NOW - 40 * DAY, RawParseStatus.PARSED)
        candidate("p6", EntrySource.SMS, "s6", 0.95, NOW - 40 * DAY + 100_000L, PendingStatus.APPROVED, NOW - 39 * DAY)

        db.appMetaDao().put(AppMetaEntity(AppMetaEntity.KEY_ROLLUP_RECONCILED_AT, (NOW - DAY).toString()))
        db.appMetaDao().put(AppMetaEntity(AppMetaEntity.KEY_ROLLUP_BUCKETS_REPAIRED, "2"))
    }

    private suspend fun report(window: DiagnosticsWindow) =
        requireNotNull(repository.snapshot(window.sinceMillis(NOW), NOW)) { "vault did not open" }

    @Test
    fun outcomes_countEachStatusInTheWindow_andStuckRegardlessOfIt() = runBlocking<Unit> {
        val captured = report(DiagnosticsWindow.LAST_30_DAYS).captured

        assertThat(captured.parsed).isEqualTo(2)
        assertThat(captured.unmatched).isEqualTo(1)
        assertThat(captured.duplicates).isEqualTo(2)
        assertThat(captured.failed).isEqualTo(0)
        assertThat(captured.waiting).isEqualTo(2)
        assertThat(captured.ignored).isEqualTo(1)
        assertThat(captured.stuck).isEqualTo(1)
    }

    /**
     * **The partition is exact**: every suppressed message is counted once,
     * under its winner's source or under null when the winner is gone, and the
     * groups sum to the outcome count above.
     */
    @Test
    fun duplicates_arePartitionedByTheKeptSource_andSumToTheOutcomeCount() = runBlocking<Unit> {
        val report = report(DiagnosticsWindow.LAST_30_DAYS)

        assertThat(report.duplicates.kept).containsExactly(
            KeptCopy(EntrySource.SMS, 1),
            KeptCopy(null, 1),
        )
        assertThat(report.duplicates.total).isEqualTo(report.captured.duplicates)
        assertThat(report.duplicates.stillInInbox).isEqualTo(1)
    }

    @Test
    fun confidence_isBucketedAtTheApprovedThresholds() = runBlocking<Unit> {
        val reading = report(DiagnosticsWindow.LAST_30_DAYS).reading

        assertThat(reading.notRecognised).isEqualTo(1)
        assertThat(reading.low).isEqualTo(2)
        assertThat(reading.medium).isEqualTo(0)
        assertThat(reading.high).isEqualTo(1)
    }

    /**
     * Two DLT headers are one bank, a package is named by the allowlist's label,
     * and the personal SMS sender never appears — it produced no candidate.
     */
    @Test
    fun weakestSenders_mergeHeaders_useAllowlistLabels_andNeverNamePersonalSenders() = runBlocking<Unit> {
        val senders = report(DiagnosticsWindow.LAST_30_DAYS).reading.weakestSenders

        assertThat(senders).containsExactly(
            SenderReadRate("Google Pay", weak = 2, total = 2),
            SenderReadRate("HDFCBK", weak = 1, total = 2),
        ).inOrder()
        assertThat(senders.map { it.sender }).doesNotContain("FRIEND")
    }

    @Test
    fun captureToInbox_isTheNearestRankMedianAndP90_perSource() = runBlocking<Unit> {
        val toInbox = report(DiagnosticsWindow.LAST_30_DAYS).speed.toInbox

        assertThat(toInbox).containsExactly(
            SourceLatency(IngestSourceType.SMS, count = 2, medianMillis = 1_000L, p90Millis = 3_000L),
            SourceLatency(IngestSourceType.NOTIFICATION, count = 2, medianMillis = 200L, p90Millis = 500L),
        )
    }

    /** The waiting queue is the Inbox's: a suppressed candidate is not waiting for anyone. */
    @Test
    fun decisions_andTheOldestWaiting_matchWhatTheInboxShows() = runBlocking<Unit> {
        val speed = report(DiagnosticsWindow.LAST_30_DAYS).speed

        assertThat(speed.decisions).isEqualTo(1)
        assertThat(speed.decisionMedianMillis).isEqualTo(HOUR)
        assertThat(speed.oldestWaitingMillis).isEqualTo(2 * DAY - 3_000L)
    }

    @Test
    fun allTime_includesWhatTheWindowLeftOut() = runBlocking<Unit> {
        val report = report(DiagnosticsWindow.ALL_TIME)

        assertThat(report.captured.parsed).isEqualTo(3)
        assertThat(report.reading.high).isEqualTo(2)
        // Three SMS delays, 1 s, 3 s and 100 s: the median is the middle one.
        assertThat(report.speed.toInbox.first { it.source == IngestSourceType.SMS })
            .isEqualTo(SourceLatency(IngestSourceType.SMS, count = 3, medianMillis = 3_000L, p90Millis = 100_000L))
        assertThat(report.speed.decisions).isEqualTo(2)
    }

    @Test
    fun rollupHealth_isReadFromAppMeta() = runBlocking<Unit> {
        assertThat(report(DiagnosticsWindow.LAST_30_DAYS).rollup)
            .isEqualTo(RollupHealth(lastReconciledAt = NOW - DAY, bucketsRepaired = 2))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
        const val NOW = 1_800_000_000_000L
        const val RETENTION = 90L * DAY
        const val GPAY = "com.google.android.apps.nbu.paisa.user"
    }
}
