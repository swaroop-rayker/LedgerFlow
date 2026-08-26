package com.ledgerflow.feature.ingest.pipeline

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ingest.FakeRawIngestRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The pipeline's P2-4 half: every message the engine resolves becomes a
 * candidate (SPEC.md §5.1, §5.2).
 *
 * These are about *what gets written*, not about what the ruleset understands —
 * `GoldenCorpusTest` is the parser's specification and this is not a second copy
 * of it. The two named tests below are the ones the step exists for: §5.1's
 * never-drop rule, and idempotency across a worker re-run.
 */
class ParseCapturedMessagesTest {

    private companion object {
        const val SENDER = "VM-HDFCBK"
        const val CAPTURED_AT = 1_700_000_000_000L
    }

    private val repository = FakeRawIngestRepository()
    private val parse = ParseCapturedMessages(repository)

    /** Matches the shape both of the owner's real HDFC messages actually have. */
    private val debitRule = ParserRule(
        id = "test-sent-debit",
        rulesetVersion = 1,
        priority = 10,
        senderPattern = "HDFCBK",
        bodyPattern = """Sent Rs\.(?<amount>[\d,.]+)[\s\S]*?A/C \*(?<accountLast4>\d{4})""" +
            """[\s\S]*?To (?<merchantRaw>[^\n]+)""",
        fieldMap = mapOf(
            ExtractionField.AMOUNT to "amount",
            ExtractionField.ACCOUNT_LAST4 to "accountLast4",
            ExtractionField.MERCHANT_RAW to "merchantRaw",
        ),
        direction = ExtractedDirection.DEBIT,
        confidenceBase = 0.9,
    )

    private suspend fun capture(body: String, sender: String = SENDER) {
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = sender,
                body = body,
                receivedAt = CAPTURED_AT,
            ),
        )
    }

    private val realDebitSms = """
        Sent Rs.788.00
        From HDFC Bank A/C *1234
        To COFFEE HOUSE
        On 14/11/23
        Ref 528612345678
    """.trimIndent()

    @Test
    fun invoke_matchedMessage_createsAReviewableCandidate() = runTest {
        repository.rules = listOf(debitRule)
        capture(realDebitSms)

        val report = parse()

        assertThat(report.parsed).isEqualTo(1)
        assertThat(report.created).isEqualTo(1)

        val candidate = repository.pending.values.single()
        assertThat(candidate.source).isEqualTo(EntrySource.SMS)
        assertThat(candidate.extracted.amount).isEqualTo(Money(78_800L))
        assertThat(candidate.extracted.direction).isEqualTo(ExtractedDirection.DEBIT)
        assertThat(candidate.needsManualFill).isFalse()
        assertThat(candidate.confidence).isGreaterThan(0.0)
    }

    /**
     * **§5.1's never-drop rule.** A financial SMS no rule understands is exactly
     * the material the ruleset has to grow against, and the owner's own first
     * real HDFC message was one of these. It reaches the Inbox with nothing
     * invented and everything flagged, or it is lost.
     */
    @Test
    fun invoke_unmatchedMessageFromAnAllowlistedSender_stillCreatesAPendingRow() = runTest {
        repository.rules = listOf(debitRule)
        capture("Some bank wording no rule in this build has ever seen.")

        val report = parse()

        assertThat(report.unmatched).isEqualTo(1)
        assertThat(report.created).isEqualTo(1)

        val candidate = repository.pending.values.single()
        assertThat(candidate.confidence).isEqualTo(0.0)
        assertThat(candidate.needsManualFill).isTrue()
        // Nothing invented. A guessed direction would file income as spend.
        assertThat(candidate.extracted.amount).isNull()
        assertThat(candidate.extracted.direction).isEqualTo(ExtractedDirection.UNKNOWN)
        assertThat(candidate.extracted.occurredAt).isNull()
    }

    /**
     * **Idempotency.** WorkManager re-runs the worker routinely — on retry, on
     * the next captured message, after a process death. A raw row that already
     * produced a candidate must not produce a second, or the user's Inbox grows
     * a copy of every transaction each time the phone wakes.
     */
    @Test
    fun invoke_runTwiceOverTheSameRawRow_createsNoSecondCandidate() = runTest {
        repository.rules = listOf(debitRule)
        capture(realDebitSms)

        val first = parse()
        val second = parse()

        assertThat(first.created).isEqualTo(1)
        assertThat(second.created).isEqualTo(0)
        assertThat(second.alreadyPending).isEqualTo(1)
        assertThat(repository.pending).hasSize(1)
    }

    /**
     * A matched rule that pulls no amount out is still not reviewable.
     *
     * This is the owner's real HDFC credit alert generalised: a shallow match
     * looks like success and is worse than a miss, because a miss is visible.
     * `needsManualFill` follows [com.ledgerflow.core.domain.ingest.ExtractedTransaction.isReviewable]
     * rather than `confidence == 0` for exactly this case.
     */
    @Test
    fun invoke_shallowMatchWithNoAmount_stillNeedsManualFill() = runTest {
        repository.rules = listOf(
            debitRule.copy(
                id = "test-shallow",
                bodyPattern = """Credit Alert!""",
                fieldMap = emptyMap(),
                direction = ExtractedDirection.CREDIT,
            ),
        )
        capture("Credit Alert! Something happened to your account.")

        parse()

        val candidate = repository.pending.values.single()
        assertThat(candidate.confidence).isGreaterThan(0.0)
        assertThat(candidate.needsManualFill).isTrue()
    }

    @Test
    fun invoke_storesTheDedupeKey_forEveryCandidate() = runTest {
        repository.rules = listOf(debitRule)
        capture(realDebitSms)

        parse()

        // §3.1's four components, with the account winning the discriminator.
        assertThat(repository.pending.values.single().dedupeKey)
            .isEqualTo("78800|DEBIT|${CAPTURED_AT / 60_000L}|1234")
    }

    @Test
    fun invoke_noRuleset_leavesTheQueueAlone() = runTest {
        repository.rules = emptyList()
        capture(realDebitSms)

        val report = parse()

        assertThat(report.total).isEqualTo(0)
        assertThat(repository.pending).isEmpty()
    }

    /**
     * A failed write leaves the raw row unresolved rather than half-resolved.
     *
     * The verdict and the candidate are one transaction, so "the write failed"
     * has to mean neither happened — otherwise the next pass would skip a row
     * whose candidate never landed, and the message would be lost with a
     * `PARSED` status claiming otherwise.
     */
    @Test
    fun invoke_whenThePendingWriteFails_recordsNoVerdictEither() = runTest {
        repository.rules = listOf(debitRule)
        repository.failPendingWrites = true
        capture(realDebitSms)

        val report = parse()

        assertThat(report.failed).isEqualTo(1)
        assertThat(report.created).isEqualTo(0)
        assertThat(repository.pending).isEmpty()
        assertThat(repository.parseOutcomes).isEmpty()
    }
}
