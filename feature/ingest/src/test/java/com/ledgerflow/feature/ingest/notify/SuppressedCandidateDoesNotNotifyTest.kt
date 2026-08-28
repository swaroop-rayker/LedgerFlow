package com.ledgerflow.feature.ingest.notify

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.testing.inbox.RecordingInboxNotifier
import com.ledgerflow.core.testing.ingest.FakeRawIngestRepository
import com.ledgerflow.feature.ingest.pipeline.ParseCapturedMessages
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **§3.1's rule, applied to the surface that can wake a phone.** P2-7.
 *
 * A single UPI payment routinely fires a bank SMS *and* a payment-app
 * notification. Dedupe already guarantees the user reviews one row rather than
 * two — `Dedupe_SameTxnAcrossSources_ProducesOnePending` covers that. This
 * covers the half P2-7 adds: **the suppressed one must not buzz.** A duplicate
 * the user never needs to see, announced anyway, is the dedupe layer defeated
 * by its own notification.
 *
 * The interesting case is the second test. The paying app's notification is
 * sparser and usually lands *first*, so the sequence is: announce the
 * notification's candidate, then the richer bank SMS arrives, wins on
 * confidence, and the incumbent becomes a suppressed row. It was legitimately
 * notified when it was the winner — so the pipeline has to take it *back*, not
 * merely decline to post a second. Leaving it in the shade would send the user
 * to review a duplicate, which is exactly the outcome dedupe exists to prevent.
 *
 * Driven through the real [ParseCapturedMessages] against the fake repository,
 * which decides suppression with the same `DuplicateMatcher` production uses.
 * The notifier is the only fake that matters here.
 */
class SuppressedCandidateDoesNotNotifyTest {

    private companion object {
        const val SENDER = "VM-HDFCBK"
        const val CAPTURED_AT = 1_700_000_000_000L
    }

    private val repository = FakeRawIngestRepository()
    private val notifier = RecordingInboxNotifier()
    private val parse = ParseCapturedMessages(repository, notifier)

    /** The richer extraction: amount, account and merchant. Confidence 0.9. */
    private val smsRule = ParserRule(
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

    /** The sparser one: amount and payee only. Confidence 0.7, so it loses. */
    private val notificationRule = ParserRule(
        id = "test-notification-paid",
        rulesetVersion = 1,
        priority = 20,
        senderPattern = "paisa",
        bodyPattern = """Paid Rs\.(?<amount>[\d,.]+) to (?<merchantRaw>[^\n]+)""",
        fieldMap = mapOf(
            ExtractionField.AMOUNT to "amount",
            ExtractionField.MERCHANT_RAW to "merchantRaw",
        ),
        direction = ExtractedDirection.DEBIT,
        confidenceBase = 0.7,
    )

    private val realDebitSms = """
        Sent Rs.788.00
        From HDFC Bank A/C *1234
        To COFFEE HOUSE
        On 14/11/23
        Ref 528612345678
    """.trimIndent()

    private suspend fun captureSms() {
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = SENDER,
                body = realDebitSms,
                receivedAt = CAPTURED_AT,
            ),
        )
    }

    private suspend fun captureNotification() {
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.NOTIFICATION,
                sender = "Google Pay",
                body = "Paid Rs.788.00 to COFFEE HOUSE",
                receivedAt = CAPTURED_AT + 8_000L,
                packageName = "com.google.android.apps.nbu.paisa.user",
            ),
        )
    }

    // ── The rule ────────────────────────────────────────────────────────────

    /**
     * One payment, two sources, **one notification**.
     *
     * Both rows are written — §3.1 retains the loser under the Inbox's
     * "Suppressed" filter — and exactly one of them is announced.
     */
    @Test
    fun onePaymentSeenByBothSources_announcesTheWinnerOnly() = runTest {
        repository.rules = listOf(smsRule, notificationRule)
        captureSms()
        captureNotification()

        val report = parse()

        // The precondition, asserted rather than assumed: if dedupe stopped
        // firing, every assertion below would pass for the wrong reason.
        assertThat(report.suppressed).isEqualTo(1)
        assertThat(repository.pending).hasSize(2)

        val live = repository.candidateIdFor(repository.liveCandidates.keys.single())
        assertThat(notifier.notified).containsExactly(live)
    }

    /**
     * The suppressed row is announced **at no point**, not merely last.
     *
     * Stated separately from the test above because "one notification" and "not
     * that one" are different claims: a pipeline that announced the loser and
     * cancelled the winner would satisfy a naive count.
     */
    @Test
    fun onePaymentSeenByBothSources_neverAnnouncesTheSuppressedRow() = runTest {
        repository.rules = listOf(smsRule, notificationRule)
        captureSms()
        captureNotification()

        parse()

        // Candidate ids, not raw ids. The two key spaces do not overlap, so
        // comparing them would make this assertion vacuously true whatever the
        // pipeline did -- see FakeRawIngestRepository.candidateIdFor.
        val suppressed = repository.suppressedBy.keys.map(repository::candidateIdFor)
        assertThat(suppressed).hasSize(1)
        assertThat(notifier.notified).containsNoneIn(suppressed)
    }

    /**
     * **The flip, and the case the `cancelCandidate` port exists for.**
     *
     * The sparse notification arrives alone and is announced — correctly, it is
     * the only candidate there is. The bank SMS lands on the next pass, scores
     * higher, and takes over; the incumbent becomes a suppressed row. Its
     * notification has to come back out of the shade.
     */
    @Test
    fun aLaterHigherConfidenceArrival_takesTheEarlierNotificationBack() = runTest {
        repository.rules = listOf(smsRule, notificationRule)

        captureNotification()
        parse()

        // It was the winner, so it was announced. That is not the bug.
        val firstAnnounced = notifier.notified.single()
        assertThat(repository.liveCandidates).hasSize(1)

        captureSms()
        val second = parse()

        // The SMS superseded it (§3.1: keep the higher-confidence extraction).
        assertThat(second.created).isEqualTo(1)
        assertThat(second.suppressed).isEqualTo(1)

        val winner = repository.candidateIdFor(repository.liveCandidates.keys.single())
        assertThat(winner).isNotEqualTo(firstAnnounced)
        // Taken back...
        assertThat(notifier.cancelled).containsExactly(firstAnnounced)
        // ...and the winner announced in its place.
        assertThat(notifier.notified).containsExactly(firstAnnounced, winner).inOrder()
    }

    /**
     * The cancel lands **before** the replacement is posted.
     *
     * Both notifications are keyed off their candidate ids, so a collision on
     * one shade slot is possible however unlikely. Posting first and cancelling
     * second would, in that case, leave the shade empty for a payment that is
     * still waiting — the one outcome §5.1 will not tolerate. Order is cheap
     * here and the failure it prevents is silent.
     */
    @Test
    fun aLaterHigherConfidenceArrival_cancelsBeforeItPosts() = runTest {
        repository.rules = listOf(smsRule, notificationRule)
        captureNotification()
        parse()
        notifier.clear()

        captureSms()
        parse()

        assertThat(notifier.events.map { it::class.simpleName })
            .containsExactly("Cancelled", "Notified")
            .inOrder()
    }

    // ── The cases that must stay quiet ──────────────────────────────────────

    /**
     * A worker re-run announces nothing.
     *
     * WorkManager retries on backoff, on the next captured message, and after a
     * process death. The user was told about this candidate on the pass that
     * created it; telling them again on every wake would turn one payment into a
     * stream of notifications for a row that has not changed.
     */
    @Test
    fun runTwiceOverTheSameRawRow_announcesItOnlyOnce() = runTest {
        repository.rules = listOf(smsRule)
        captureSms()

        parse()
        parse()

        assertThat(notifier.notified).hasSize(1)
    }

    /**
     * A write that failed announces nothing.
     *
     * There is no row, so there is nothing to review, and a notification whose
     * deep link resolves to no candidate is worse than silence — the raw row
     * kept its `CAPTURED` status and the next pass will try again.
     */
    @Test
    fun whenThePendingWriteFails_announcesNothing() = runTest {
        repository.rules = listOf(smsRule)
        repository.failPendingWrites = true
        captureSms()

        val report = parse()

        assertThat(report.failed).isEqualTo(1)
        assertThat(notifier.notified).isEmpty()
    }

    /**
     * §5.1's never-drop row **is** announced.
     *
     * An unparseable message from an allowlisted sender is the one the user most
     * needs to know about — nothing was extracted, so nothing else will remind
     * them it happened. Keeping it out of the shade would make "never silently
     * dropped" true of the database and false of the user's experience.
     */
    @Test
    fun unmatchedMessageFromAnAllowlistedSender_isStillAnnounced() = runTest {
        repository.rules = listOf(smsRule)
        repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = SENDER,
                body = "Some bank wording no rule in this build has ever seen.",
                receivedAt = CAPTURED_AT,
            ),
        )

        val report = parse()

        assertThat(report.unmatched).isEqualTo(1)
        assertThat(notifier.notified)
            .containsExactly(repository.candidateIdFor(repository.pending.keys.single()))
    }
}
