package com.ledgerflow.core.domain.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [JaroWinkler], pinned to the published reference vectors.
 *
 * **Pinned to the literature rather than to this implementation's output**,
 * which is the whole point: an algorithm with a name has a right answer that
 * does not depend on us. Recording what the code happens to return would make
 * this a restatement, and a later "optimisation" that quietly changed the
 * window rule or dropped the transposition term would keep it green while
 * moving every threshold in the app.
 *
 * The vectors are Winkler's own worked examples, as reproduced in the standard
 * references and in Apache Commons Text's test suite.
 */
class JaroWinklerTest {

    private companion object {
        /** These are ratios read off published tables; 4 places is the precision they are quoted to. */
        const val TOLERANCE = 1e-4
    }

    @Test
    fun jaro_matchesThePublishedVectors() {
        // The canonical transposition example: same letters, one swap.
        assertThat(JaroWinkler.jaro("MARTHA", "MARHTA")).isWithin(TOLERANCE).of(0.9444)
        assertThat(JaroWinkler.jaro("DIXON", "DICKSONX")).isWithin(TOLERANCE).of(0.7667)
        assertThat(JaroWinkler.jaro("DWAYNE", "DUANE")).isWithin(TOLERANCE).of(0.8222)
        // No shared prefix, so Winkler adds nothing and this is also the JW score.
        assertThat(JaroWinkler.jaro("CRATE", "TRACE")).isWithin(TOLERANCE).of(0.7333)
    }

    @Test
    fun similarity_matchesThePublishedVectors() {
        assertThat(JaroWinkler.similarity("MARTHA", "MARHTA")).isWithin(TOLERANCE).of(0.9611)
        assertThat(JaroWinkler.similarity("DIXON", "DICKSONX")).isWithin(TOLERANCE).of(0.8133)
        assertThat(JaroWinkler.similarity("DWAYNE", "DUANE")).isWithin(TOLERANCE).of(0.8400)
        assertThat(JaroWinkler.similarity("CRATE", "TRACE")).isWithin(TOLERANCE).of(0.7333)
    }

    @Test
    fun similarity_ofEqualStrings_isOne() {
        assertThat(JaroWinkler.similarity("GRAND TOTAL", "GRAND TOTAL")).isEqualTo(1.0)
        assertThat(JaroWinkler.similarity("", "")).isEqualTo(1.0)
    }

    @Test
    fun similarity_withNothingInCommon_isZero() {
        assertThat(JaroWinkler.similarity("ABC", "XYZ")).isEqualTo(0.0)
        assertThat(JaroWinkler.similarity("TOTAL", "")).isEqualTo(0.0)
        assertThat(JaroWinkler.similarity("", "TOTAL")).isEqualTo(0.0)
    }

    @Test
    fun similarity_isSymmetric() {
        val pairs = listOf(
            "MARTHA" to "MARHTA",
            "DIXON" to "DICKSONX",
            "S GST" to "S 6ST",
            "ZEPTO PVT LTD" to "ZEPTO",
            "TOMATO 1KG" to "TOMAT0 1KG",
        )

        pairs.forEach { (a, b) ->
            assertThat(JaroWinkler.similarity(a, b))
                .isWithin(TOLERANCE)
                .of(JaroWinkler.similarity(b, a))
        }
    }

    /**
     * The prefix boost applies only above a Jaro of 0.7 — Winkler's own floor,
     * and the half most modern libraries drop.
     *
     * `CRATE`/`TRACE` is the vector that shows it cannot be dropped silently:
     * its Jaro is 0.7333, just *above* the floor, and it has no common prefix,
     * so it pins the boost's input rather than the gate. The gate itself is
     * pinned below.
     */
    @Test
    fun similarity_belowTheBoostFloor_isNotLiftedByASharedPrefix() {
        // Jaro is under 0.7 and the strings share three leading characters, so
        // an ungated boost would add 0.3 * (1 - jaro) and pull a poor match up.
        val jaro = JaroWinkler.jaro("ABCDEF", "ABCXYZ")

        assertThat(jaro).isLessThan(0.7)
        assertThat(JaroWinkler.similarity("ABCDEF", "ABCXYZ")).isEqualTo(jaro)
    }

    /** And above the floor the prefix genuinely does lift the score. */
    @Test
    fun similarity_aboveTheBoostFloor_isLiftedByASharedPrefix() {
        val jaro = JaroWinkler.jaro("MARTHA", "MARHTA")

        assertThat(jaro).isGreaterThan(0.7)
        assertThat(JaroWinkler.similarity("MARTHA", "MARHTA")).isGreaterThan(jaro)
    }

    /**
     * The prefix bonus stops counting at four characters.
     *
     * Without the cap, a long shared prefix would drive any pair toward 1.0 and
     * `ZEPTO MARKETPLACE` would match `ZEPTO MARINE SUPPLIES` — §5.5's
     * conservative-merge rule turned inside out.
     */
    @Test
    fun similarity_countsAtMostFourPrefixCharacters() {
        // Two pairs constructed to have the SAME Jaro (eight of nine characters
        // match in each) and different shared prefixes: four characters, then
        // eight. The cap is what makes the scores identical.
        val sharesFour = "ABCDZEFGH" to "ABCDYEFGH"
        val sharesEight = "ABCDEFGHZ" to "ABCDEFGHY"

        assertThat(JaroWinkler.jaro(sharesFour.first, sharesFour.second))
            .isWithin(TOLERANCE)
            .of(JaroWinkler.jaro(sharesEight.first, sharesEight.second))

        val four = JaroWinkler.similarity(sharesFour.first, sharesFour.second)
        val eight = JaroWinkler.similarity(sharesEight.first, sharesEight.second)

        assertThat(four).isWithin(TOLERANCE).of(eight)
        // And the lift is real, so this is not two unboosted scores agreeing.
        assertThat(four).isGreaterThan(JaroWinkler.jaro(sharesFour.first, sharesFour.second))
    }

    /**
     * §12's stated example, and it clears §5.5's threshold comfortably — which
     * is the reason that section is allowed to concede a tolerance on names.
     */
    @Test
    fun similarity_ofTheSpecsOwnOcrExample_clearsTheMerchantThreshold() {
        assertThat(JaroWinkler.similarity("TOMATO 1KG", "TOMAT0 1KG"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /**
     * Devanagari is compared like any other text.
     *
     * ADR-0021 ships the Devanagari recogniser and §5.3's keyword sets hold
     * `बिल राशि` and `कुल` literally, so a similarity function that mangled
     * combining marks would make those entries behave differently from the
     * Latin ones for no stated reason.
     */
    @Test
    fun similarity_handlesDevanagari() {
        assertThat(JaroWinkler.similarity("बिल राशि", "बिल राशि")).isEqualTo(1.0)
        assertThat(JaroWinkler.similarity("कुल योग", "कुल")).isLessThan(1.0)
        assertThat(JaroWinkler.similarity("कुल", "बिल")).isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /**
     * **The case this was built for.** A real Food Bazaar invoice printed
     * `S GST 9%` and ML Kit read `S 6ST 9%`.
     *
     * Two facts, and both matter. The spaced five-character form clears §5.5's
     * threshold at 0.8933, so keyword matching needs no threshold of its own.
     * The bare three-character `GST` scores 0.7778 and must not — because
     * `GET`, a different word entirely, scores **0.80** against `GST`, above
     * the real misread. At three characters the score does not merely fail to
     * separate the two, it ranks them backwards, and no threshold can fix
     * that. Which is why `ReceiptKeywords` requires equal length and at most
     * one differing character on top of the threshold, and why the TAX set
     * lists the spaced forms the invoice actually prints.
     */
    @Test
    fun similarity_theSpacedGstForm_clearsTheThresholdAndTheBareOneDoesNot() {
        assertThat(JaroWinkler.similarity("S 6ST", "S GST"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)

        assertThat(JaroWinkler.similarity("6ST", "GST"))
            .isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
        // **And a genuinely different word scores HIGHER than the real OCR
        // error**, which is the point rather than a coincidence: `GET` shares
        // `GST`'s first letter and collects the prefix boost (0.80), while the
        // misread `6ST` does not (0.7778). No threshold can separate those two,
        // in either direction, so at three characters the score is not evidence.
        assertThat(JaroWinkler.similarity("GET", "GST"))
            .isGreaterThan(JaroWinkler.similarity("6ST", "GST"))
        assertThat(JaroWinkler.similarity("GET", "GST"))
            .isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /**
     * A single substituted character is worth less on a short string, stated as
     * a monotonic fact rather than left implicit in a threshold.
     *
     * This is the evidence for `ReceiptKeywords`' design: 0.88 is itself a
     * length floor, because no single substitution can reach it below four
     * characters.
     */
    @Test
    fun similarity_ofOneSubstitution_risesWithLength() {
        val scores = (3..8).map { length ->
            val keyword = "ABCDEFGH".take(length)
            JaroWinkler.similarity("Z" + keyword.drop(1), keyword)
        }

        assertThat(scores).isInOrder()
        assertThat(scores.first()).isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
        assertThat(scores.last()).isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
    }
}
