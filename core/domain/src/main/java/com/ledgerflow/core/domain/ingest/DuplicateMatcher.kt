package com.ledgerflow.core.domain.ingest

import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer

/**
 * Whether two candidates in the same [DedupeKey] bucket are one transaction
 * seen twice (SPEC.md §3.1). P2-5.
 *
 * ## Compatible, not equal
 *
 * The two sources describe the same payment with different fields, not with
 * different values of the same fields: a bank SMS names the account, the paying
 * app's notification names the payee, and neither carries what the other has.
 * Requiring equality on a shared discriminator therefore cannot work — that is
 * the measurement in [DedupeKey]'s KDoc.
 *
 * So the rule is **contradiction, not agreement**. Two candidates already share
 * an amount and a direction (the key put them in the same bucket) and are within
 * the window. They are the same transaction *unless a field they both carry
 * disagrees*. A field one side simply lacks proves nothing and never blocks the
 * match.
 *
 * ## What this deliberately accepts
 *
 * Two genuinely different payments of the same amount and direction, inside
 * three minutes, to the same merchant or on the same account, will merge. That
 * case is real — two ₹50 top-ups to one shop — and it is accepted rather than
 * engineered away, because the alternative is failing to merge the case this
 * exists for. §3.1 makes it recoverable rather than lossy: the suppressed row is
 * **retained and visible** under the Inbox's "Suppressed" filter, never
 * discarded, so a user who sees one row where they made two payments can find
 * the other. A missed merge shows the user a duplicate; a wrong merge hides a
 * row that is still there. Neither is free, and only one is invisible.
 *
 * Note what is *not* here: no check on which adapter produced either candidate.
 * §3.1 calls this cross-source dedupe, but restricting it to differing sources
 * would miss a real same-source case — a bank that sends both a "debited" and a
 * "UPI" SMS for one payment — and it would put a source comparison in the one
 * layer CLAUDE.md §0 says must not have one.
 */
public object DuplicateMatcher {

    /**
     * §3.1's ±3 minutes, applied to **capture time** rather than to the
     * transaction's stated time.
     *
     * Capture time is what the two sources actually share: they observe the same
     * payment within seconds of each other, whatever the message claims about
     * when it happened. `occurredAt` is frequently a bare date (see [DedupeKey])
     * and belongs to the ledger, not to this.
     */
    public const val WINDOW_MILLIS: Long = 3L * 60L * 1000L

    /**
     * True when nothing the two candidates both carry contradicts.
     *
     * Callers must have established the shared bucket and the window first; this
     * answers only the remaining question.
     */
    public fun isSameTransaction(
        candidate: ExtractedTransaction,
        other: ExtractedTransaction,
    ): Boolean {
        if (contradicts(candidate.accountLast4, other.accountLast4) { it.trim() }) return false
        if (contradicts(candidate.merchantRaw, other.merchantRaw, MerchantNormalizer::normalize)) {
            return false
        }
        // A reference number is the strongest evidence either way when both
        // sides have one -- it is the rail's own identifier for the payment.
        if (contradicts(candidate.referenceNo, other.referenceNo) { it.trim() }) return false
        return true
    }

    /**
     * Two values disagree only when **both** are present and non-blank after
     * normalising. Absence is not disagreement.
     *
     * Blank counts as absent: a rule whose optional group matched nothing yields
     * `""` rather than null often enough that treating the two differently would
     * make the outcome depend on how a regex was written.
     */
    private inline fun contradicts(
        left: String?,
        right: String?,
        normalise: (String) -> String,
    ): Boolean {
        val a = left?.let(normalise)?.takeIf { it.isNotEmpty() } ?: return false
        val b = right?.let(normalise)?.takeIf { it.isNotEmpty() } ?: return false
        return !a.equals(b, ignoreCase = true)
    }
}
