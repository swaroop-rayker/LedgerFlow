package com.ledgerflow.core.domain.ingest

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * How the money moved, as the parser read it (SPEC.md §5.1).
 *
 * A separate type from [LedgerType] on purpose: the ledger is a decision the
 * *user* makes at approval, and this is only what a regex saw in a sentence.
 * [UNKNOWN] is a real answer — a rule that matched an amount but no verb should
 * say so rather than guess a book, because guessing wrong files income as spend.
 */
public enum class ExtractedDirection {
    DEBIT,
    CREDIT,
    UNKNOWN,
    ;

    /** Null when the parser could not tell. The review screen then asks. */
    public fun toLedgerOrNull(): LedgerType? = when (this) {
        DEBIT -> LedgerType.DEBIT
        CREDIT -> LedgerType.CREDIT
        UNKNOWN -> null
    }
}

/** How the payment was made, where the message says (SPEC.md §5.1). */
public enum class InstrumentHint {
    UPI,
    CARD,
    NETBANKING,
    ATM,
    WALLET,
    UNKNOWN,
}

/**
 * What one rule pulled out of one message (SPEC.md §5.1's extraction targets).
 *
 * **Everything is nullable except [direction] and [confidence], and that is the
 * design.** A bank SMS is a sentence, not a form: plenty carry an amount and no
 * merchant, or a merchant and no reference. §5.1 requires that an unparseable
 * message from an allowlisted sender still becomes a `PENDING` row with
 * `confidence = 0` rather than being dropped, so this type has to be able to
 * represent "almost nothing was found" without being invalid.
 *
 * It lives in `:core:domain` rather than in `:feature:ingest` because the Inbox
 * has to render it at P2-6, and features may not depend on features
 * (CLAUDE.md §3).
 *
 * @param amount always in the message's own currency, which is [currency] and
 *   **not** necessarily the base currency. Converting is not this layer's job
 *   and there is no FX engine (D-02); a foreign-currency message reaches the
 *   review screen with its original amount, where the user supplies the base
 *   figure.
 * @param merchantRaw the merchant exactly as written. Never normalised here —
 *   §5.1 resolves it through `MerchantRepository.createOrGet` at approval, and
 *   the fuzzy match is a *suggestion* at review time, never a gate.
 * @param occurredAt the transaction's own time when the message states one, and
 *   null otherwise. Deliberately distinct from the capture time on
 *   [RawIngestEvent]: a delayed SMS would otherwise land in the wrong day.
 * @param confidence 0.0 when nothing matched. A score, not money — Law 3 bans
 *   `Double` for amounts, not for everything.
 */
public data class ExtractedTransaction(
    val amount: Money? = null,
    val currency: String? = null,
    val direction: ExtractedDirection = ExtractedDirection.UNKNOWN,
    val merchantRaw: String? = null,
    val accountLast4: String? = null,
    val instrumentHint: InstrumentHint = InstrumentHint.UNKNOWN,
    val referenceNo: String? = null,
    val occurredAt: Long? = null,
    val availableBalance: Money? = null,
    val confidence: Double = 0.0,
) {

    /**
     * Enough to put in front of the user without asking them to start over.
     *
     * An amount and a direction are the two a review screen cannot invent. §5.1
     * still keeps everything else — a message with neither becomes a
     * `needs_manual_fill` row rather than nothing at all.
     */
    public val isReviewable: Boolean
        get() = amount != null && direction != ExtractedDirection.UNKNOWN
}
