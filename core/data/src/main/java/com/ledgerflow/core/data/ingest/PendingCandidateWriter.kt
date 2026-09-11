package com.ledgerflow.core.data.ingest

import androidx.room.withTransaction
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.PendingTransactionDao
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.DuplicateMatcher
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one path a candidate takes into `pending_transaction` (§3.1, §5.1, §5.3).
 *
 * ## Why this is its own class
 *
 * It was private to `DefaultRawIngestRepository` until OCR arrived and needed
 * the identical write. Reimplementing the dedupe for receipts would have been
 * the easy thing and exactly wrong: §3.1's whole point is that **one payment
 * can now be observed three ways** — a bank SMS, a payment-app notification,
 * and a photograph of the paper bill. Three writers would have meant three
 * chances to disagree about what "the same transaction" is, and the
 * disagreement would surface as duplicate rows on a user's device rather than
 * as a failing test.
 *
 * So the sharing is made structural rather than incidental. There is one
 * insert, one dedupe, one transaction, and the callers differ in a single
 * parameter.
 *
 * ## The transaction is the idempotency guarantee
 *
 * The pipeline's queue is "raw rows still at `CAPTURED`", so a re-run only
 * revisits a row whose verdict was never written — and because the verdict and
 * the candidate land together, a row that has a verdict provably has its
 * candidate. Without the transaction, a process death between the two would
 * leave either a lost candidate or, on the next pass, a second one.
 */
@Singleton
public class PendingCandidateWriter @Inject constructor(
    private val clock: Clock,
    private val ids: Uuid7Generator,
) {

    /**
     * Whether this capture left a row in a raw table, and what the engine made
     * of it.
     *
     * **Null is not a source check** (CLAUDE.md §0). The branch below asks
     * "is there a raw row to stamp", which is a structural fact about the
     * capture, not "was this an SMS". A receipt genuinely has no raw ingest
     * row — its bytes are an `attachment` — and stamping `parse_status` onto
     * two tables by an id that matches neither would be a write that silently
     * does nothing.
     */
    public data class RawVerdict(val ruleId: String?)

    /**
     * The one insert path, shared by every source (§3.1, §5.1, §5.3).
     *
     * Extracted when OCR arrived. Reimplementing the dedupe for receipts would
     * have been the easy thing and exactly wrong: §3.1's whole point is that a
     * single UPI payment fires a bank SMS *and* a payment-app notification,
     * and now possibly a photographed receipt too. Three writers would have
     * meant three chances to disagree about what "the same transaction" is.
     */
    public suspend fun write(
        database: LedgerFlowDatabase,
        refId: String,
        candidate: PendingCandidate,
        verdict: RawVerdict?,
    ): PendingWriteOutcome {
        val pendingDao = database.pendingTransactionDao()

        return runCatching {
            // One transaction, and that is what makes the worker idempotent:
            // the queue is "raw rows still at CAPTURED", so a row that carries a
            // verdict provably carries its candidate too. A process death
            // between the two writes would otherwise lose a candidate, or
            // deposit a second one on the next pass.
            database.withTransaction {
                val existing = pendingDao.idForRawRef(refId)
                if (existing != null) {
                    // Belt and braces on top of the transaction. parse_status is
                    // an ordinary column that a future maintenance path could
                    // reset; the duplicate Inbox row that would follow is
                    // invisible to everything except a user counting.
                    return@withTransaction PendingWriteOutcome.AlreadyPending(existing)
                }

                val pendingId = ids.generate()
                val now = clock.nowMillis()

                // §3.1's cross-source dedupe. Inside the same transaction as the
                // insert, deliberately: two messages for one payment routinely
                // arrive seconds apart, and a check that ran before the write
                // could let both pass and both insert.
                val winner = findDuplicate(pendingDao, candidate, now)
                pendingDao.insert(
                    PendingTransactionEntity(
                        id = pendingId,
                        source = candidate.source,
                        dedupeKey = candidate.dedupeKey,
                        // Non-null when this candidate lost a dedupe. The row
                        // is still written and still visible -- §3.1 keeps the
                        // loser under the Inbox's "Suppressed" filter, because a
                        // duplicate the user cannot see is indistinguishable
                        // from a message that was dropped.
                        suppressedById = winner?.takeIf { it.confidence >= candidate.confidence }
                            ?.id,
                        // The parameter, not a field on the candidate: this is
                        // the same id the check above used and the same row the
                        // verdict lands on, so there is one value and no way for
                        // two of them to disagree.
                        rawRefId = refId,
                        extractedJson = ExtractedTransactionJson.encode(candidate.extracted),
                        confidence = candidate.confidence,
                        // Law 1: the only status an automated source may write.
                        // APPROVED belongs to ApproveTransactionUseCase and
                        // DISCARDED to the user; FAILED is written by nothing --
                        // see the port's KDoc.
                        status = PendingStatus.PENDING,
                        needsManualFill = candidate.needsManualFill,
                        createdAt = now,
                        reviewedAt = null,
                        approvedEntryId = null,
                    ),
                )

                // The row is in one of the two raw tables and the id says nothing
                // about which. Updating both is cheaper and simpler than carrying a
                // source around -- and keeps this free of the `if (source == SMS)`
                // CLAUDE.md §0 forbids outside an adapter.
                //
                // Skipped entirely when there is no raw row (OCR): an update by
                // an attachment id matches nothing, and a write that silently
                // affects zero rows is the shape of bug §7 keeps warning about.
                if (verdict != null) {
                    val status =
                        if (verdict.ruleId != null) RawParseStatus.PARSED else RawParseStatus.UNMATCHED
                    database.smsRawDao().updateStatus(refId, status, verdict.ruleId)
                    database.notificationRawDao().updateStatus(refId, status, verdict.ruleId)
                }

                when {
                    winner == null -> PendingWriteOutcome.Created(pendingId)

                    // The incumbent scored at least as well: this arrival is the
                    // duplicate. Ties keep the row that was already there, which
                    // makes the outcome independent of delivery order.
                    winner.confidence >= candidate.confidence -> {
                        if (verdict != null) markRawDuplicateSuppressed(database, refId)
                        PendingWriteOutcome.Suppressed(pendingId, winner.id)
                    }

                    // §3.1: keep the higher-confidence extraction. The richer
                    // message usually arrives second -- the paying app notifies
                    // first and sparsely, the bank SMS follows with the account
                    // and the reference -- so this is the common path, not the
                    // exotic one.
                    else -> {
                        // Refuses on an APPROVED or DISCARDED incumbent, which is
                        // the point: that row has been decided by a human and may
                        // already have a `ledger_entry` behind it.
                        val flipped = pendingDao.suppress(winner.id, pendingId) > 0
                        if (flipped) {
                            // The loser's own raw row, whatever produced it.
                            // Harmless and correct when that was a receipt: the
                            // update matches no raw row and the attachment is
                            // not a raw row to mark.
                            markRawDuplicateSuppressed(database, winner.rawRefId)
                            PendingWriteOutcome.Created(pendingId, supersededPendingId = winner.id)
                        } else {
                            // The incumbent could not be suppressed, so it stands
                            // and this one yields to it rather than both standing.
                            pendingDao.suppress(pendingId, winner.id)
                            if (verdict != null) markRawDuplicateSuppressed(database, refId)
                            PendingWriteOutcome.Suppressed(pendingId, winner.id)
                        }
                    }
                }
            }
        }.getOrElse { throwable ->
            // The raw row keeps CAPTURED, so the next pass retries it. Returning
            // rather than throwing is the same rule the rest of this class
            // follows: a worker that throws is a crash in a background process.
            PendingWriteOutcome.Failed(throwable.message ?: throwable::class.java.name)
        }
    }

    /**
     * The candidate this one is a duplicate of, or null.
     *
     * Two stages, and the split is §3.1's own: the key puts candidates in a
     * bucket and the window bounds it, both served by one index scan; then
     * [DuplicateMatcher] decides whether anything the two both carry actually
     * contradicts. An unkeyed candidate -- no amount extracted -- can never
     * match, which is what stops two unparseable messages in one window from
     * suppressing each other and turning §5.1's never-drop rule into a drop.
     *
     * The **highest-confidence** compatible row wins rather than the nearest in
     * time, so that a third arrival is judged against the best of the group.
     */
    private suspend fun findDuplicate(
        dao: PendingTransactionDao,
        candidate: PendingCandidate,
        now: Long,
    ): PendingTransactionEntity? {
        if (DedupeKey.isUnkeyed(candidate.dedupeKey)) return null

        return dao.inDedupeWindow(
            key = candidate.dedupeKey,
            from = now - DuplicateMatcher.WINDOW_MILLIS,
            to = now + DuplicateMatcher.WINDOW_MILLIS,
        )
            .filter { row ->
                val extracted = ExtractedTransactionJson.decode(row.extractedJson)
                // A payload this build cannot read is not evidence of a
                // duplicate. Treating an unreadable row as a match would
                // suppress a good candidate on the strength of a bad one.
                extracted != null &&
                    DuplicateMatcher.isSameTransaction(candidate.extracted, extracted)
            }
            .maxByOrNull { it.confidence }
    }

    /**
     * §3.1: the suppressed candidate's raw row records `DUPLICATE_SUPPRESSED`.
     *
     * Both tables again, for the same reason the verdict write does it: the id
     * says nothing about which table holds the row, and asking would mean
     * branching on source.
     */
    private suspend fun markRawDuplicateSuppressed(
        database: LedgerFlowDatabase,
        rawId: String?,
    ) {
        val id = rawId ?: return
        database.smsRawDao().updateStatusOnly(id, RawParseStatus.DUPLICATE_SUPPRESSED)
        database.notificationRawDao().updateStatusOnly(id, RawParseStatus.DUPLICATE_SUPPRESSED)
    }

    /**
     * A stored rule as the engine wants it, or null if the row is unusable.
     *
     * Null rather than throwing: one bad row -- a hand-edited rule with a typo
     * in its field map -- must not stop every other rule from loading.
     */
}
