package com.ledgerflow.feature.ingest.pipeline

import com.ledgerflow.core.domain.ingest.RawIngestRepository
import com.ledgerflow.feature.ingest.parser.ExtractionResult
import com.ledgerflow.feature.ingest.parser.ParserRuleEngine
import javax.inject.Inject

/** What one parse pass made of the queue. */
public data class ParseReport(val parsed: Int, val unmatched: Int) {
    public val total: Int get() = parsed + unmatched
}

/**
 * Runs the rule engine over everything captured but not yet resolved
 * (SPEC.md §5.1, §5.2).
 *
 * **Lives in `:feature:ingest`, not in a `:core:domain` use case**, and that is
 * the module rule rather than an accident: the engine is here, `:core:domain`
 * may not see it, and a domain use case that needed it would drag the parser
 * into the layer every feature depends on. The repository port is how this
 * reaches the database, so the direction of dependency stays right.
 *
 * **What it does not do yet.** It records the verdict on the raw row —
 * `matched_rule_id` and `PARSED` / `UNMATCHED` — and creates no
 * `pending_transaction`. §5.1's rule that an unparseable message from an
 * allowlisted sender still becomes a `PENDING` row with `confidence = 0` is
 * satisfied by the next step; this one makes the engine live and observable
 * without inventing rows the Inbox cannot yet show.
 *
 * The ruleset is loaded **once per pass**, not once per message: the engine
 * compiles every regex on construction, and doing that per message in a worker
 * that may wake to a backlog would be the expensive kind of obviously-correct.
 */
public class ParseCapturedMessages @Inject constructor(
    private val repository: RawIngestRepository,
) {

    public suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): ParseReport {
        val rules = repository.parserRules()
        if (rules.isEmpty()) return ParseReport(parsed = 0, unmatched = 0)

        val engine = ParserRuleEngine(rules)
        var parsed = 0
        var unmatched = 0

        repository.capturedEvents(limit).forEach { captured ->
            when (val result = engine.extract(captured.event)) {
                is ExtractionResult.Matched -> {
                    repository.recordParseOutcome(captured.rawId, result.ruleId, matched = true)
                    parsed++
                }

                ExtractionResult.Unmatched -> {
                    // Recorded, never dropped (§5.1). This is the row a future
                    // rule will be written against, and the one the review
                    // screen will ask the user to fill in by hand.
                    repository.recordParseOutcome(captured.rawId, ruleId = null, matched = false)
                    unmatched++
                }
            }
        }
        return ParseReport(parsed, unmatched)
    }

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
