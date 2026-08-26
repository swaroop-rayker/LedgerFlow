package com.ledgerflow.core.domain.ingest

import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer

/**
 * §3.1's cross-source dedupe key: `(amountMinor, direction,
 * roundToMinute(occurredAt), accountLast4 ?: merchantNormalized)`.
 *
 * **A UPI payment commonly fires both a bank SMS and a GPay notification.** This
 * is the value that lets P2-5 notice, and it is computed here — in `:core:domain`,
 * pure Kotlin — rather than beside the parser, because P2-5 needs the identical
 * function to look a candidate up and two implementations of one key would
 * disagree exactly when it mattered.
 *
 * P2-4 computes and stores it. **Acting on a collision is P2-5**, including the
 * ±3 minute window: the minute bucket below is one component of the key, and the
 * window is a range scan over `(dedupe_key, created_at)` around it, not something
 * this function can express.
 *
 * The key is deliberately *not* a hash. `sms_raw.body_hash` is a hash because it
 * stands in for a whole message body; this is four short fields, and keeping them
 * legible means a support question about why two rows did or did not merge can be
 * answered by reading the column.
 */
public object DedupeKey {

    /**
     * The key for one extraction, or a key that can never collide when there is
     * nothing to match on.
     *
     * @param capturedAt when the device captured the message, used **only** when
     *   the message states no date of its own. Owner decision at P2-4:
     *   [ExtractedTransaction.occurredAt] stays null in the stored payload, so
     *   the review screen shows an empty date and asks rather than asserting a
     *   time the bank never gave — but the key falls back to capture time, which
     *   is sound because its whole job is "same transaction, two sources, within
     *   three minutes" and both sources capture within seconds of each other.
     *   Being wrong by a day matters for the ledger date; it does not matter for
     *   a ±3 minute collision window.
     * @param rawRefId the raw row, used only for the no-amount fallback below.
     */
    public fun compute(
        extracted: ExtractedTransaction,
        capturedAt: Long,
        rawRefId: String,
    ): String {
        val amount = extracted.amount
            // No amount means there is nothing for §3.1's key to be about, and a
            // content key built from four blanks would make every unparseable
            // message in one minute look like a duplicate of every other. That
            // is the §5.1 never-drop rule defeated one step later: the second
            // unmatched HDFC alert of the minute would be suppressed as a copy
            // of the first. A key that cannot collide is the honest answer, and
            // it makes P2-5's lookup skip these rows without needing to know why.
            ?: return "$UNKEYED_PREFIX$rawRefId"

        val minute = (extracted.occurredAt ?: capturedAt).floorDivMinute()

        return listOf(
            amount.minor.toString(),
            // UNKNOWN is a component value, not a hole. §3.1 lists direction, and
            // two UNKNOWNs of the same amount in the same minute for the same
            // account genuinely are one payment seen twice.
            extracted.direction.name,
            minute.toString(),
            extracted.discriminator(),
        ).joinToString(SEPARATOR)
    }

    /**
     * True for a key that exists only to be unique — see [compute].
     *
     * P2-5 reads this rather than re-deriving the rule.
     */
    public fun isUnkeyed(key: String): Boolean = key.startsWith(UNKEYED_PREFIX)

    /**
     * §3.1's `accountLast4 ?: merchantNormalized`.
     *
     * The account wins because it is the field both sources agree on: a bank SMS
     * and the paying app's notification describe the same merchant with
     * different strings far more often than they disagree about the last four
     * digits of the account.
     *
     * [MerchantNormalizer] rather than a local lowercase, because it is already
     * the function that decides what counts as "the same merchant" (§5.5) and a
     * second opinion here would let dedupe and the taxonomy disagree.
     */
    private fun ExtractedTransaction.discriminator(): String {
        accountLast4?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        merchantRaw?.let(MerchantNormalizer::normalize)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return ""
    }

    /**
     * Floor rather than round-half-up, despite §3.1's word "round".
     *
     * A bucket boundary has to be a function of the timestamp alone or the two
     * sources can land in different buckets for timestamps two milliseconds
     * apart; flooring is the only reading where `59.9s` and `60.1s` differ by
     * exactly one bucket, which is what the ±3 minute window is sized against.
     */
    private fun Long.floorDivMinute(): Long = Math.floorDiv(this, MILLIS_PER_MINUTE)

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val SEPARATOR = "|"

    /** Marks a key that carries no extracted content. See [compute]. */
    private const val UNKEYED_PREFIX = "raw:"
}
