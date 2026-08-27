package com.ledgerflow.core.domain.ingest

/**
 * §3.1's cross-source dedupe key — the *bucket* half of it. P2-4 stores it, P2-5
 * matches on it.
 *
 * **A UPI payment commonly fires both a bank SMS and a GPay notification**, and
 * this is what lets P2-5 notice. It lives in `:core:domain`, pure Kotlin, rather
 * than beside the parser, because the writer and the matcher both need the
 * identical function and two implementations of one key would disagree exactly
 * when it mattered.
 *
 * ## What §3.1 asked for, and why this is not that
 *
 * The spec's key is
 * `(amountMinor, direction, roundToMinute(occurredAt), accountLast4 ?: merchantNormalized)`.
 * Measured against the real corpus, **both of its variable components diverge by
 * source**, so as written it can never match an SMS against a notification:
 *
 * - **The minute.** `DateText` resolves a bare `On 27/08/26` through
 *   `LocalDate.atStartOfDay`, so a real HDFC debit's `occurredAt` is *midnight*.
 *   A notification for the same payment extracts no date at all (0 of 5 matched
 *   notification fixtures do) and falls back to capture time. The two buckets
 *   coincide only for a payment made in the first minute of a day.
 * - **The discriminator.** SMS carries `accountLast4` (14 of 16 matched
 *   fixtures); notifications never do (0 of 5). `accountLast4 ?: merchantNormalized`
 *   therefore *guarantees* the two sources pick different fields — the one
 *   component meant to identify a transaction is the one they cannot share.
 *
 * So the minute leaves the key and the discriminator leaves the key, and each is
 * handled where it actually works: the ±3 minute window is a range scan on
 * `created_at`, and the discriminator becomes [DuplicateMatcher]'s
 * contradiction check. What remains here is the coarse bucket — amount and
 * direction — which is exactly what the committed
 * `Index("dedupe_key", "created_at")` was shaped for. Had the minute belonged in
 * the key, `created_at` would not need to be in that index at all.
 *
 * The key is deliberately **not** a hash. `sms_raw.body_hash` is a hash because
 * it stands in for a whole message body; this is two short fields, and keeping
 * them legible means a support question about why two rows did or did not merge
 * can be answered by reading the column.
 */
public object DedupeKey {

    /**
     * The bucket one extraction falls in, or a key that can never collide when
     * there is nothing to bucket on.
     *
     * @param rawRefId the raw row, used only for the no-amount fallback below.
     */
    public fun compute(extracted: ExtractedTransaction, rawRefId: String): String {
        val amount = extracted.amount
            // No amount means there is nothing for §3.1's key to be about, and a
            // bucket built from blanks would make every unparseable message in
            // the window look like a duplicate of every other. That is §5.1's
            // never-drop rule defeated one step later: the second unmatched HDFC
            // alert of the minute would be suppressed as a copy of the first. A
            // key that cannot collide is the honest answer, and it makes the
            // window lookup skip these rows without needing to know why.
            ?: return "$UNKEYED_PREFIX$rawRefId"

        // Direction is a component rather than a filter: §3.1 lists it, and it is
        // what stops a ₹500 credit from ever being suppressed against a ₹500
        // debit in the same window. UNKNOWN is its own bucket, not a hole.
        return "${amount.minor}$SEPARATOR${extracted.direction.name}"
    }

    /**
     * True for a key that exists only to be unique — see [compute].
     *
     * The window lookup reads this rather than re-deriving the rule.
     */
    public fun isUnkeyed(key: String): Boolean = key.startsWith(UNKEYED_PREFIX)

    private const val SEPARATOR = "|"

    /** Marks a key that carries no extracted content. See [compute]. */
    private const val UNKEYED_PREFIX = "raw:"
}
