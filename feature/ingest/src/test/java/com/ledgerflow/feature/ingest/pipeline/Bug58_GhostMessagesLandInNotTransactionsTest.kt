package com.ledgerflow.feature.ingest.pipeline

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.model.NonTransactionKind
import com.ledgerflow.core.testing.inbox.RecordingInboxNotifier
import com.ledgerflow.core.testing.ingest.FakeRawIngestRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **BUG58 — "ghost" Inbox entries** (V2-PLAN BUG-B, decision D6).
 *
 * OTPs, balance alerts, statement and bill reminders, and payment apps' own
 * offers come from allowlisted senders, match no rule, and each landed in the
 * queue as a `PENDING` row with no payee and no amount — and buzzed the phone.
 *
 * D6, confirmed by the owner: a recognised non-transaction is **retained and
 * visible** under the Inbox's "Not transactions" filter, never deleted, kept
 * out of the main queue, and never announced. This is the pipeline half:
 *
 * - the row is still written (§5.1's never-drop rule), labelled with its kind;
 * - it is never notified;
 * - and a real transaction, or a message the classifier cannot vouch for, is
 *   untouched — still unlabelled, still announced.
 *
 * The Inbox half (out of the queue, back with one tap) is
 * `Bug58_NotTransactionsFilterTest` in `:feature:inbox`; the SQL half is
 * `InboxFilterCountsMatchTest` and `MigrationV11ToV12Test`.
 */
class Bug58_GhostMessagesLandInNotTransactionsTest {

    private val repository = FakeRawIngestRepository()
    private val notifier = RecordingInboxNotifier()
    private val parse = ParseCapturedMessages(repository, notifier)

    private val debitRule = ParserRule(
        id = "test-sent-debit",
        rulesetVersion = 1,
        priority = 10,
        senderPattern = "HDFCBK",
        bodyPattern = """Sent Rs\.(?<amount>[\d,.]+)[\s\S]*?To (?<merchantRaw>[^\n]+)""",
        fieldMap = mapOf(
            ExtractionField.AMOUNT to "amount",
            ExtractionField.MERCHANT_RAW to "merchantRaw",
        ),
        direction = ExtractedDirection.DEBIT,
        confidenceBase = 0.9,
    )

    private var clock = 1_700_000_000_000L

    private suspend fun capture(body: String) {
        // A minute apart, so capture-level dedupe never folds two test messages.
        clock += 60_000L
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = "VM-HDFCBK-S",
                body = body,
                receivedAt = clock,
            ),
        )
    }

    private suspend fun captureNotification(body: String) {
        clock += 60_000L
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.NOTIFICATION,
                sender = "Google Pay",
                body = body,
                receivedAt = clock,
                packageName = "com.google.android.apps.nbu.paisa.user",
            ),
        )
    }

    @Test
    fun ghostsAreWrittenLabelledAndNeverAnnounced() = runTest {
        repository.rules = listOf(debitRule)
        capture("482913 is your OTP for login. Valid for 10 minutes. Do not share with anyone.")
        capture("Your a/c XX2211 balance as on 26-08-26 is Rs.18,300.25.")
        capture("Statement for card XX4471: Total due Rs.24,500, due date 18/09/26.")
        captureNotification("Scratch card unlocked! Play now to win up to Rs.1,000")

        val report = parse()

        // Never dropped: four messages, four rows.
        assertThat(report.created).isEqualTo(4)
        assertThat(report.notTransactions).isEqualTo(4)
        assertThat(repository.pending.values.map { it.nonTransactionKind }).containsExactly(
            NonTransactionKind.OTP,
            NonTransactionKind.BALANCE,
            NonTransactionKind.STATEMENT_OR_DUE,
            NonTransactionKind.PROMO,
        )
        // Nothing invented on the way.
        repository.pending.values.forEach { candidate ->
            assertThat(candidate.extracted.amount).isNull()
            assertThat(candidate.confidence).isEqualTo(0.0)
        }
        // The point of the bug: not one of them buzzes the phone.
        assertThat(notifier.notified).isEmpty()
    }

    @Test
    fun aRealTransactionIsStillAnnouncedAndUnlabelled() = runTest {
        repository.rules = listOf(debitRule)
        capture("Sent Rs.788.00\nFrom HDFC Bank A/C *1234\nTo COFFEE HOUSE")

        parse()

        val candidate = repository.pending.values.single()
        assertThat(candidate.nonTransactionKind).isNull()
        assertThat(notifier.notified).hasSize(1)
    }

    /**
     * The classifier is biased to say nothing. An unmatched alert that says
     * money moved stays a ghost in the queue — announced, unlabelled — even
     * though it also mentions an OTP, because filing a payment under "Not
     * transactions" is the worse mistake.
     */
    @Test
    fun anUnmatchedDebitThatMentionsAnOtpStaysInTheQueue() = runTest {
        repository.rules = listOf(debitRule)
        capture(
            "Rs.2,000 debited from a/c XX1234 on 12-10-26 at AMAZON. " +
                "Never share your OTP 1234 with anyone.",
        )

        parse()

        val candidate = repository.pending.values.single()
        assertThat(candidate.nonTransactionKind).isNull()
        assertThat(notifier.notified).hasSize(1)
    }

    @Test
    fun wordingNobodyRecognisesIsStillAnOrdinaryGhost() = runTest {
        repository.rules = listOf(debitRule)
        capture("Some bank wording no rule in this build has ever seen.")

        val report = parse()

        assertThat(report.notTransactions).isEqualTo(0)
        assertThat(repository.pending.values.single().nonTransactionKind).isNull()
        assertThat(notifier.notified).hasSize(1)
    }
}
