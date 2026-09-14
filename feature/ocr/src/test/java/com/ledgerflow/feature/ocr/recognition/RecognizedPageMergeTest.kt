package com.ledgerflow.feature.ocr.recognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Merging the Latin and Devanagari passes over one image.
 *
 * ## Why this is a JVM test
 *
 * It is arithmetic over rectangles with a correct answer, which is exactly what
 * `ReceiptTextRecognizer` returning [RecognizedElement] rather than ML Kit's own
 * types was for. Running it on a device would test the recogniser and this
 * function at once, and a failure would not say which.
 *
 * ## The failure it exists to prevent
 *
 * Both script models read Latin digits. On an ordinary receipt they therefore
 * return the *same* runs, and a naive concatenation would list every amount
 * twice — a bill that reads as costing double, with nothing anywhere saying so.
 * That is a Law 3-adjacent defect: the money is `Long` and correct, and the
 * *count* of it is wrong.
 */
class RecognizedPageMergeTest {

    private fun element(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        script: RecognitionScript = RecognitionScript.LATIN,
    ) = RecognizedElement(text, left, top, right, bottom, script)

    private fun page(vararg elements: RecognizedElement) = RecognizedPage(elements.toList())

    @Test
    fun twoModelsReadingTheSameRun_produceOneElement() {
        val latin = page(element("473.00", 300f, 100f, 420f, 130f))
        // The same glyphs, boxed a couple of pixels differently -- which is what
        // two models actually do, and why the threshold is not exact equality.
        val devanagari = page(
            element("473.00", 302f, 101f, 421f, 131f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements).hasSize(1)
    }

    /**
     * And the surviving copy is Latin's.
     *
     * Not arbitrary: on a tie the model specialised for the script that was
     * actually read is the one to trust.
     */
    @Test
    fun onATie_theLatinReadingSurvives() {
        val latin = page(element("TOTAL", 40f, 100f, 160f, 130f))
        val devanagari = page(
            element("TOTAL", 41f, 100f, 161f, 131f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements.single().script).isEqualTo(RecognitionScript.LATIN)
    }

    /**
     * **The entire point of the second pass.**
     *
     * A Devanagari run the Latin model could not see at all has no overlapping
     * box, so it survives. If this failed, bundling the second model would buy
     * nothing.
     */
    @Test
    fun aRunOnlyOneModelSaw_isKept() {
        val latin = page(element("473.00", 300f, 100f, 420f, 130f))
        val devanagari = page(
            element("किराना", 40f, 100f, 200f, 132f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements).hasSize(2)
        assertThat(merged.elements.map { it.text }).containsExactly("473.00", "किराना")
    }

    /**
     * Same region, different readings: the more complete one wins.
     *
     * The Latin model resolving a Devanagari word as a couple of stray marks is
     * the ordinary case, and the longer reading is the one that saw more of what
     * was on the paper. Flagged in the KDoc as the one heuristic here a corpus
     * should confirm rather than a rule that is obviously right.
     */
    @Test
    fun overlappingButDifferent_theLongerReadingWins() {
        val latin = page(element("ch", 40f, 100f, 200f, 132f))
        val devanagari = page(
            element("चावल", 41f, 101f, 199f, 131f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements.single().text).isEqualTo("चावल")
        assertThat(merged.elements.single().script).isEqualTo(RecognitionScript.DEVANAGARI)
    }

    /**
     * Two genuinely different runs that happen to sit near each other stay two.
     *
     * An item name and its price on the same line are adjacent and must never
     * collapse — that would drop a column §5.3 depends on.
     */
    @Test
    fun adjacentButNotOverlapping_bothSurvive() {
        val latin = page(
            element("RICE 5KG", 40f, 100f, 260f, 130f),
            element("420.00", 300f, 100f, 420f, 130f),
        )

        val merged = RecognizedPage.merge(latin, RecognizedPage(emptyList()))

        assertThat(merged.elements).hasSize(2)
    }

    /** Touching edges is not overlapping. */
    @Test
    fun boxesThatOnlyTouch_areNotTheSameRun() {
        val latin = page(element("A", 0f, 0f, 100f, 100f))
        val devanagari = page(element("ब", 100f, 0f, 200f, 100f, RecognitionScript.DEVANAGARI))

        assertThat(RecognizedPage.merge(latin, devanagari).elements).hasSize(2)
    }

    /**
     * A partial overlap below the threshold keeps both.
     *
     * Two stacked lines on a tight thermal receipt overlap slightly and are
     * genuinely different runs; collapsing them would lose a line.
     */
    @Test
    fun aSmallOverlap_keepsBoth() {
        val latin = page(element("MILK", 40f, 100f, 200f, 132f))
        // Shares only the bottom sliver -- roughly 0.1 IoU.
        val devanagari = page(
            element("ब्रेड", 40f, 128f, 200f, 160f, RecognitionScript.DEVANAGARI),
        )

        assertThat(RecognizedPage.merge(latin, devanagari).elements).hasSize(2)
    }

    @Test
    fun mergingWithAnEmptyPage_changesNothing() {
        val latin = page(element("TOTAL", 40f, 100f, 160f, 130f))

        assertThat(RecognizedPage.merge(latin, RecognizedPage(emptyList())).elements).hasSize(1)
        assertThat(RecognizedPage.merge(RecognizedPage(emptyList()), latin).elements).hasSize(1)
    }

    @Test
    fun mergingTwoEmptyPages_isEmpty() {
        assertThat(
            RecognizedPage.merge(RecognizedPage(emptyList()), RecognizedPage(emptyList())).isEmpty,
        ).isTrue()
    }

    /**
     * A whole receipt's worth: every amount appears exactly once.
     *
     * The end-to-end form of the defect at the top of this file. Both models
     * read all four Latin runs; the Devanagari model additionally reads the
     * shop's name in Devanagari. Six readings in, five elements out.
     */
    @Test
    fun aTypicalMixedReceipt_countsEveryAmountOnce() {
        val latin = page(
            element("RICE", 40f, 200f, 200f, 230f),
            element("420.00", 300f, 200f, 420f, 230f),
            element("TOTAL", 40f, 260f, 200f, 290f),
            element("473.00", 300f, 260f, 420f, 290f),
        )
        val devanagari = page(
            element("किराना स्टोर", 40f, 40f, 300f, 80f, RecognitionScript.DEVANAGARI),
            element("420.00", 301f, 201f, 421f, 231f, RecognitionScript.DEVANAGARI),
            element("473.00", 301f, 261f, 421f, 291f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements).hasSize(5)
        assertThat(merged.elements.count { it.text == "473.00" }).isEqualTo(1)
        assertThat(merged.elements.count { it.text == "420.00" }).isEqualTo(1)
        assertThat(merged.elements.map { it.text }).contains("किराना स्टोर")
    }

    // ─── BUG22: the second model's digit lookalikes ─────────────────────────

    /**
     * **BUG22, as the device produced it.** On a real A4 invoice the Devanagari
     * model returned `০.০০` (Bengali zeroes) beside the Latin `0.00`, boxed far
     * enough apart that the overlap rule kept both. The row then read
     * `০.০০ 0.00`. A run with no Devanagari letter is dropped before it can be
     * merged at all.
     */
    @Test
    fun bug22_aDigitLookalikeFromTheDevanagariPass_isNotMerged() {
        val latin = page(element("0.00", 300f, 100f, 360f, 130f))
        val devanagari = page(
            // Shifted enough to fall under SAME_RUN_OVERLAP -- the real failure.
            element("০.০০", 280f, 104f, 336f, 136f, RecognitionScript.DEVANAGARI),
            element("२", 40f, 100f, 60f, 130f, RecognitionScript.DEVANAGARI),
            element("৪.20", 500f, 100f, 560f, 130f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(latin, devanagari)

        assertThat(merged.elements.map { it.text }).containsExactly("0.00")
    }

    /**
     * And it cannot *replace* a Latin reading by being longer either — the
     * other door the lookalikes came through.
     */
    @Test
    fun bug22_aLongerLookalike_doesNotReplaceTheLatinReading() {
        val latin = page(element("0.14", 300f, 100f, 360f, 130f))
        val devanagari = page(
            element("0 01८ 14", 301f, 101f, 361f, 131f, RecognitionScript.DEVANAGARI),
        )

        assertThat(RecognizedPage.merge(latin, devanagari).elements.single().text)
            .isEqualTo("0.14")
    }

    /**
     * Devanagari digits alone are figures, not words, so they do not qualify;
     * a Devanagari word with a figure in it does.
     */
    @Test
    fun bug22_devanagariWordsStillQualify_andDevanagariDigitsAloneDoNot() {
        val devanagari = page(
            element("५००", 40f, 40f, 100f, 70f, RecognitionScript.DEVANAGARI),
            element("चावल ५किलो", 40f, 100f, 260f, 130f, RecognitionScript.DEVANAGARI),
        )

        val merged = RecognizedPage.merge(RecognizedPage(emptyList()), devanagari)

        assertThat(merged.elements.map { it.text }).containsExactly("चावल ५किलो")
    }
}
