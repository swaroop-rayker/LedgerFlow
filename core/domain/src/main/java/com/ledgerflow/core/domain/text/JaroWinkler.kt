package com.ledgerflow.core.domain.text

/**
 * Jaro-Winkler string similarity (SPEC.md §5.5, §12).
 *
 * One implementation, three callers, which is why it is here rather than in any
 * of them:
 *
 * - **§5.5's merchant suggestion**, at [MERCHANT_THRESHOLD]. An extracted
 *   header or SMS `merchantRaw` normalises through `MerchantNormalizer` and then
 *   compares against existing aliases. It is a *suggestion* at review time and
 *   never a gate on committing an entry — a wrong auto-merge is the expensive
 *   correction, a duplicate the cheap one.
 * - **§5.3's receipt keyword matching**, in `ReceiptKeywords`, at its own
 *   stricter 0.89 and after folding OCR-confusable digits — a caller policy,
 *   stated and measured there, which is exactly why it is not done here.
 * - **§12's recall grading**, when the receipt corpus arrives: a hit needs the
 *   item name to match after `ItemNameNormalizer`, because OCR legitimately
 *   reads `TOMATO 1KG` as `TOMAT0 1KG`. Money never gets a tolerance.
 *
 * **Pure, and no normalisation of its own.** It compares the two strings it is
 * given, code unit by code unit, case-sensitively. Case folding, accent folding
 * and suffix stripping are the callers' business and they disagree about all
 * three — `MerchantNormalizer` is deliberately conservative because it decides a
 * `UNIQUE` column, `ItemNameNormalizer` deliberately is not. Folding here would
 * silently apply one caller's policy to the others.
 *
 * **Law 3 is not in play.** A similarity score is a ratio, not money; the ban is
 * on `Float`/`Double` for a monetary amount (CLAUDE.md §2 Law 3), and this is
 * one of the real-valued quantities that rule explicitly carves out.
 */
public object JaroWinkler {

    /**
     * §5.5's number: `Jaro-Winkler ≥ 0.88`.
     *
     * Stated once so the callers that use §5.5's number cannot drift apart.
     * Raising it is a spec change, not a tuning knob. Keyword matching does not
     * use it: short keywords need a stricter figure, derived in `ReceiptKeywords`.
     */
    public const val MERCHANT_THRESHOLD: Double = 0.88

    /**
     * The Winkler prefix weight and the Jaro floor it applies above.
     *
     * Both are from Winkler's original definition. The `0.7` gate is the half
     * modern libraries often drop (Apache Commons' `JaroWinklerSimilarity`
     * boosts unconditionally), and it is kept because dropping it only ever
     * raises a score: a pair that is already a poor match should not be pulled
     * upward for happening to start with the same letter, and every caller here
     * is using the score as a *threshold* rather than a ranking.
     */
    private const val PREFIX_WEIGHT = 0.1
    private const val PREFIX_BOOST_FLOOR = 0.7
    private const val MAX_PREFIX = 4

    /**
     * Jaro is the mean of three terms: the matched fraction of each string, and
     * the fraction of matches that are in order.
     */
    private const val TERMS = 3.0

    /** Jaro-Winkler similarity in `0.0..1.0`. `1.0` iff the strings are equal. */
    public fun similarity(a: String, b: String): Double {
        val jaro = jaro(a, b)
        if (jaro < PREFIX_BOOST_FLOOR) return jaro
        val prefix = commonPrefixLength(a, b)
        return jaro + prefix * PREFIX_WEIGHT * (1.0 - jaro)
    }

    /**
     * The unboosted Jaro similarity.
     *
     * Two characters match when they are equal and no further apart than
     * `max(len) / 2 - 1` positions — the window is what makes Jaro tolerate a
     * shifted run rather than scoring it as a wholesale rewrite. A
     * *transposition* is a pair matched out of order, and counts as half an
     * error each, which is why `MARTHA`/`MARHTA` scores so much higher than two
     * independent substitutions would.
     *
     * Internal: it exists to be asserted against the published reference
     * vectors in `JaroWinklerTest`, not to be called. Callers want
     * [similarity].
     */
    internal fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        val matches = markMatches(a, b, aMatched, bMatched)
        if (matches == 0) return 0.0

        val transpositions = countTranspositions(a, b, aMatched, bMatched)
        val m = matches.toDouble()
        return (
            m / a.length +
                m / b.length +
                (m - transpositions) / m
            ) / TERMS
    }

    /**
     * Flags each matched position in both strings and returns how many there
     * are.
     *
     * Fills the two arrays rather than returning a pair of them: they are read
     * again by [countTranspositions], which needs to know *which* positions
     * matched and not merely how many. Greedy and left-to-right, which is the
     * algorithm as defined — the first unclaimed equal character inside the
     * window wins.
     */
    private fun markMatches(
        a: String,
        b: String,
        aMatched: BooleanArray,
        bMatched: BooleanArray,
    ): Int {
        val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        var matches = 0
        for (i in a.indices) {
            val from = (i - window).coerceAtLeast(0)
            val to = (i + window).coerceAtMost(b.length - 1)
            val j = (from..to).firstOrNull { !bMatched[it] && a[i] == b[it] } ?: continue
            aMatched[i] = true
            bMatched[j] = true
            matches++
        }
        return matches
    }

    /**
     * Walks the two matched subsequences in parallel; every position where they
     * disagree is *half* a transposition, because a swap shows up once from
     * each side.
     */
    private fun countTranspositions(
        a: String,
        b: String,
        aMatched: BooleanArray,
        bMatched: BooleanArray,
    ): Int {
        var halves = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) halves++
            k++
        }
        return halves / 2
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(MAX_PREFIX, a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }
}
