package com.ledgerflow.feature.ingest.pipeline

import com.ledgerflow.core.domain.inbox.InboxNotifier
import com.ledgerflow.core.domain.ingest.CapturedEvent
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import com.ledgerflow.feature.ingest.parser.ExtractionResult
import com.ledgerflow.feature.ingest.parser.ParserRuleEngine
import javax.inject.Inject

/**
 * What one parse pass made of the queue.
 *
 * [parsed] and [unmatched] count *verdicts*; the rest count what those verdicts
 * produced. They are reported separately rather than assumed equal because the
 * differences are the interesting numbers: [suppressed] is §3.1's cross-source
 * dedupe firing, [alreadyPending] is the worker re-running over work it
 * finished, and [failed] is a row that kept its `CAPTURED` status and will be
 * tried again.
 *
 * [created] and [suppressed] both mean a row was written. Nothing the pipeline
 * resolves goes uncounted, and nothing it resolves goes unwritten.
 */
public data class ParseReport(
    val parsed: Int,
    val unmatched: Int,
    val created: Int = 0,
    val suppressed: Int = 0,
    val alreadyPending: Int = 0,
    val failed: Int = 0,
) {
    public val total: Int get() = parsed + unmatched
}

/**
 * Runs the rule engine over everything captured but not yet resolved, and turns
 * each verdict into the candidate the user will review (SPEC.md §5.1, §5.2).
 * P2-4.
 *
 * **Lives in `:feature:ingest`, not in a `:core:domain` use case**, and that is
 * the module rule rather than an accident: the engine is here, `:core:domain`
 * may not see it, and a domain use case that needed it would drag the parser
 * into the layer every feature depends on. The repository port is how this
 * reaches the database, so the direction of dependency stays right.
 *
 * **Every message that reaches this class becomes a `pending_transaction` row**,
 * matched or not. That is §5.1's never-drop rule, and the unmatched path is the
 * whole point of it: a bank SMS no rule understands still arrives in the Inbox
 * with `confidence = 0` and `needs_manual_fill = 1`, where the user can enter it
 * by hand and where it stays as the material a future rule is written against.
 * A message from a sender that is *not* allowlisted never reaches here — triage
 * takes it out of the queue first (§5.1), and a notification from a
 * non-allowlisted package was never captured at all (§5.2).
 *
 * **Cross-source dedupe happens beneath this class, not in it** (§3.1). The
 * repository decides on the write, inside the same transaction as the insert,
 * because two messages for one payment routinely arrive seconds apart and a
 * check made up here could let both pass. What this class does is compute the
 * key and count the outcome -- a suppressed candidate is still a row, still
 * visible under the Inbox's "Suppressed" filter, and never a drop.
 *
 * **Nothing here reaches the ledger.** Law 1: a candidate waits for a human, and
 * only `ApproveTransactionUseCase` may insert into `ledger_entry`.
 *
 * The ruleset is loaded **once per pass**, not once per message: the engine
 * compiles every regex on construction, and doing that per message in a worker
 * that may wake to a backlog would be the expensive kind of obviously-correct.
 */
public class ParseCapturedMessages @Inject constructor(
    private val repository: RawIngestRepository,
    private val notifier: InboxNotifier,
) {

    public suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): ParseReport {
        val rules = repository.parserRules()
        // No ruleset means every message would be recorded as UNMATCHED against
        // a ruleset that never ran, which is a verdict the corpus could not
        // distinguish from a real miss. Leaving the rows CAPTURED is honest and
        // the next pass, after seeding, resolves them.
        if (rules.isEmpty()) return ParseReport(parsed = 0, unmatched = 0)

        val engine = ParserRuleEngine(rules)
        var parsed = 0
        var unmatched = 0
        var created = 0
        var suppressed = 0
        var alreadyPending = 0
        var failed = 0

        repository.capturedEvents(limit).forEach { captured ->
            val ruleId: String?
            val extracted: ExtractedTransaction

            when (val result = engine.extract(captured.event)) {
                is ExtractionResult.Matched -> {
                    ruleId = result.ruleId
                    extracted = result.extracted
                    parsed++
                }

                ExtractionResult.Unmatched -> {
                    ruleId = null
                    // The default is §5.1's `confidence = 0` candidate: no
                    // amount, no direction, nothing invented. `needsManualFill`
                    // follows from it rather than being set here, so the
                    // matched-but-useless case gets the same treatment.
                    extracted = ExtractedTransaction()
                    unmatched++
                }
            }

            val outcome = repository.recordParseOutcome(
                captured.rawId,
                ruleId,
                captured.candidate(extracted),
            )
            when (outcome) {
                is PendingWriteOutcome.Created -> {
                    created++
                    // A flip: this arrival scored higher than one already there,
                    // so the incumbent became the suppressed row (§3.1). Counted
                    // here so the two numbers still add up to rows written.
                    if (outcome.supersededPendingId != null) suppressed++
                    notifyCreated(outcome)
                }

                // §3.1, and P2-7's sharpest rule: a suppressed duplicate is
                // retained and visible under the Inbox's "Suppressed" filter,
                // and is NEVER announced. One UPI payment routinely fires a bank
                // SMS and a GPay notification; buzzing twice for it is the
                // failure the dedupe layer exists to prevent, arriving through
                // a different surface. `SuppressedCandidateDoesNotNotifyTest`.
                is PendingWriteOutcome.Suppressed -> suppressed++

                // The worker re-running over work it already finished. The user
                // was told about this candidate on the pass that created it, and
                // telling them again on every WorkManager retry would turn one
                // payment into a stream of notifications.
                is PendingWriteOutcome.AlreadyPending -> alreadyPending++

                is PendingWriteOutcome.Failed -> failed++
            }
        }
        return ParseReport(parsed, unmatched, created, suppressed, alreadyPending, failed)
    }

    /**
     * §5.1's last step, and §3.1's other half.
     *
     * The new candidate is announced. The **superseded** one is taken back: it
     * was legitimately notified when it was the winner, and a later arrival with
     * a higher-confidence extraction has since made it a suppressed row. Leaving
     * it in the shade would send the user to review a duplicate — the same
     * double-announcement the [PendingWriteOutcome.Suppressed] branch refuses,
     * arriving in the order where the sparse message lands first.
     *
     * Order matters: cancel before posting, so that if the two notifications
     * collide on a slot the surviving one is the winner's.
     */
    private suspend fun notifyCreated(outcome: PendingWriteOutcome.Created) {
        outcome.supersededPendingId?.let { notifier.cancelCandidate(it) }
        notifier.notifyCandidate(outcome.pendingId)
    }

    /**
     * The candidate one captured message becomes.
     *
     * `toEntrySource()` rather than a `when` on the source type: the mapping
     * lives on the enum in `:core:domain` so that this package — which is
     * downstream of every capture adapter — contains no branch on where a
     * message came from at all (CLAUDE.md §0).
     *
     * The merchant is **not** resolved here. §5.1's `createOrGet` runs at
     * approval (owner decision, P2-4), so a candidate the user discards leaves
     * no merchant behind and §5.5's fuzzy suggestion still has something to
     * suggest at review time.
     */
    private fun CapturedEvent.candidate(extracted: ExtractedTransaction) = PendingCandidate(
        source = event.sourceType.toEntrySource(),
        extracted = extracted,
        // Amount and direction only. The ±3 minute window and the
        // account/merchant comparison live where they work -- on `created_at`
        // and in DuplicateMatcher -- because measured against the real corpus
        // both of §3.1's other components diverge by source. See DedupeKey.
        dedupeKey = DedupeKey.compute(extracted, rawId),
    )

    private companion object {
        /**
         * A bound rather than "everything", for the same reason the triage pass
         * has one: a worker waking to a backlog should make progress and finish
         * rather than hold the device for an unbounded time. WorkManager re-runs
         * it.
         */
        const val DEFAULT_LIMIT = 200
    }
}
