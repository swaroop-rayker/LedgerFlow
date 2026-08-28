package com.ledgerflow.core.domain.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import org.junit.Test

/**
 * **BUG15 — a correction reached the review screen and nowhere else.**
 *
 * Reported by the owner as "the autosave seems to be not working in review".
 * It was working: edit an amount, leave, come back, and the edit is there. What
 * was broken is that **every other surface still showed the parser's figure** —
 * the Inbox row, the Ledger's "Unsaved" row, and the order that section sorts
 * in. From outside the review screen an edited candidate looked untouched,
 * which is indistinguishable from typing that never saved.
 *
 * The cause was a scoping mistake: the payload was written as
 * `:feature:inbox`'s own, on the reasoning that form state belongs to the
 * screen producing it (SPEC.md §6.1.2's split for `draft_entry.payload_json`).
 * That holds for a draft and not for a *candidate*, because a candidate is a
 * row other surfaces list. [ReviewEdits] moved to `:core:domain` and
 * [PendingTransaction.effective] is what those surfaces now render.
 *
 * **[PendingTransaction.extracted] deliberately still means "what the parser
 * read".** §5.1's targets are spec-level, the review screen shows them, and the
 * corpus is written against them — so corrections are laid *over* rather than
 * *into* them. Half of what follows checks that the original survives.
 */
class Bug15_EditsReachEverySurfaceTest {

    private val parsed = ExtractedTransaction(
        amount = Money(100L),
        currency = "INR",
        direction = ExtractedDirection.DEBIT,
        merchantRaw = "KARUNAKAR RAYKER",
        accountLast4 = "6402",
        occurredAt = 1_787_000_000_000L,
        confidence = 0.9,
    )

    private fun candidate(
        edits: ReviewEdits? = null,
        editedMerchantName: String? = null,
    ) = PendingTransaction(
        id = "p1",
        source = EntrySource.SMS,
        extracted = parsed,
        confidence = 0.9,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_787_000_000_000L,
        reviewedAt = null,
        approvedEntryId = null,
        edits = edits,
        editedMerchantName = editedMerchantName,
    )

    // ── The bug ─────────────────────────────────────────────────────────────

    /** The reported case, exactly: ₹1.00 corrected to ₹7.77. */
    @Test
    fun aCorrectedAmount_isWhatEverySurfaceShows() {
        val row = candidate(ReviewEdits(amountText = "7.77", amountMinor = 777L))

        assertThat(row.effective.amount).isEqualTo(Money(777L))
        // ...and the message still says what it said.
        assertThat(row.extracted.amount).isEqualTo(Money(100L))
    }

    @Test
    fun aCorrectedDate_isWhatEverySurfaceShows() {
        val corrected = 1_787_500_000_000L
        val row = candidate(ReviewEdits(occurredAt = corrected))

        assertThat(row.effective.occurredAt).isEqualTo(corrected)
        assertThat(row.extracted.occurredAt).isEqualTo(1_787_000_000_000L)
    }

    /**
     * A chosen merchant shows by **name**, not by id.
     *
     * The edits hold a `merchantId` and every list renders a name, so the
     * repository resolves it on the way out. Without that the row would either
     * show a UUID or fall back to the message's payee — which is what "I
     * changed the merchant and the list did not" would look like.
     */
    @Test
    fun aChosenMerchant_showsItsNameNotItsId() {
        val row = candidate(
            edits = ReviewEdits(merchantId = "m-42"),
            editedMerchantName = "Swiggy",
        )

        assertThat(row.effective.merchantRaw).isEqualTo("Swiggy")
        assertThat(row.extracted.merchantRaw).isEqualTo("KARUNAKAR RAYKER")
    }

    /**
     * A book the user chose becomes the row's direction.
     *
     * §5.1's never-drop row arrives with `UNKNOWN`, and the list colours and
     * signs the amount from the direction — so until the user's choice reached
     * `effective`, filing an unreadable message as income left the row still
     * rendering as neither.
     */
    @Test
    fun aChosenBook_becomesTheRowsDirection() {
        val unreadable = candidate().copy(
            extracted = ExtractedTransaction(confidence = 0.0),
            needsManualFill = true,
            edits = ReviewEdits(ledger = LedgerType.CREDIT, amountText = "50", amountMinor = 5_000L),
        )

        assertThat(unreadable.effective.direction).isEqualTo(ExtractedDirection.CREDIT)
        assertThat(unreadable.extracted.direction).isEqualTo(ExtractedDirection.UNKNOWN)
    }

    // ── What an edit must not touch ─────────────────────────────────────────

    /**
     * Facts about the message survive any edit.
     *
     * The account, the reference and the confidence describe what *arrived*.
     * A correction is about the transaction, not about what the bank sent, and
     * the Inbox's provenance line reads these — `SMS · A/C 6402` must not start
     * changing because someone fixed an amount.
     */
    @Test
    fun editingSomething_leavesTheMessagesOwnFactsAlone() {
        val row = candidate(ReviewEdits(amountText = "7.77", amountMinor = 777L))

        assertThat(row.effective.accountLast4).isEqualTo("6402")
        assertThat(row.effective.currency).isEqualTo("INR")
        assertThat(row.effective.confidence).isEqualTo(0.9)
    }

    /**
     * A partially-filled edit falls back field by field, not wholesale.
     *
     * Someone who corrects only the date keeps the parser's amount and payee.
     * An overlay that replaced the whole extraction would blank the two fields
     * the row is mostly made of.
     */
    @Test
    fun anEditOfOneField_leavesTheOthersAsTheParserReadThem() {
        val row = candidate(ReviewEdits(occurredAt = 1_787_500_000_000L))

        assertThat(row.effective.amount).isEqualTo(Money(100L))
        assertThat(row.effective.merchantRaw).isEqualTo("KARUNAKAR RAYKER")
        assertThat(row.effective.direction).isEqualTo(ExtractedDirection.DEBIT)
    }

    /**
     * An amount typed but not yet valid does not blank the row.
     *
     * Mid-keystroke the text can be `7.` or empty, which parses to nothing.
     * `amountMinor` is null then, and the row keeps showing the message's
     * figure rather than a confident `₹0.00` — the debounce fires while people
     * are still typing.
     */
    @Test
    fun aHalfTypedAmount_leavesTheParsersFigureShowing() {
        val row = candidate(ReviewEdits(amountText = "7.", amountMinor = null))

        assertThat(row.effective.amount).isEqualTo(Money(100L))
    }

    // ── No edits at all ─────────────────────────────────────────────────────

    @Test
    fun withNoEdits_effectiveIsExactlyTheExtraction() {
        val row = candidate()

        assertThat(row.effective).isEqualTo(parsed)
        assertThat(row.isEdited).isFalse()
    }

    @Test
    fun withEdits_theRowSaysSo() {
        assertThat(candidate(ReviewEdits(noteText = "split with Anita")).isEdited).isTrue()
    }
}
