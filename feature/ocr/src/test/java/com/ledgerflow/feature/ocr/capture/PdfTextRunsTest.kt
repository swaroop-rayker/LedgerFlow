package com.ledgerflow.feature.ocr.capture

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures
import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import org.junit.Test

/**
 * [PdfTextRuns] — rebuilding positions from a text layer that only offers
 * substring search.
 *
 * The fake [search] behaves like `PdfRenderer.Page.searchText` measured on the
 * device: every occurrence of the token **as a substring**, each returned as
 * the box of the matched characters — so `0.00` inside `50.00` comes back as a
 * box sitting inside the `50.00` word. That property is the entire difficulty,
 * so the fake reproduces it rather than returning whole words.
 */
class PdfTextRunsTest {

    /** A "PDF" whose words are the given runs; search is substring search over them. */
    private fun searchOver(words: List<RecognizedElement>): (String) -> List<List<PdfTextRuns.Box>> = { token ->
        words.flatMap { word ->
            val advance = (word.right - word.left) / word.text.length
            Regex(Regex.escape(token), RegexOption.IGNORE_CASE).findAll(word.text).map { match ->
                listOf(
                    PdfTextRuns.Box(
                        left = word.left + match.range.first * advance,
                        top = word.top,
                        right = word.left + (match.range.last + 1) * advance,
                        bottom = word.bottom,
                    ),
                )
            }.toList()
        }
    }

    private fun word(text: String, left: Float, top: Float = 0f) =
        RecognizedElement(text, left, top, left + text.length * 10f, top + 20f)

    /**
     * **The substring trap.** `0.00` matches inside `50.00` and `10.00`; only the
     * standalone `0.00` is a run of its own. Longest-first claiming is what
     * drops the other two.
     */
    @Test
    fun aShortTokenInsideALongerWord_isNotARunOfItsOwn() {
        val words = listOf(word("50.00", 0f), word("0.00", 100f), word("10.00", 200f))
        val text = words.joinToString(" ") { it.text }

        val page = PdfTextRuns.build(text, scale = 1f, search = searchOver(words))

        assertThat(page.elements.map { it.text }).containsExactly("50.00", "0.00", "10.00")
        assertThat(page.elements.single { it.text == "0.00" }.left).isEqualTo(100f)
    }

    /** A repeated token yields one run per printed occurrence, at each place. */
    @Test
    fun aRepeatedToken_isARunAtEveryOccurrence() {
        val words = listOf(word("2.50%", 0f), word("2.50%", 100f), word("2.50%", 0f, top = 40f))
        val page = PdfTextRuns.build("2.50% 2.50% 2.50%", scale = 1f, search = searchOver(words))

        assertThat(page.elements).hasSize(3)
    }

    /** Coordinates are scaled onto the rendered page the attachment stores. */
    @Test
    fun coordinates_areScaledOntoTheRenderedPage() {
        val words = listOf(word("TOTAL", 10f, top = 5f))
        val element = PdfTextRuns.build("TOTAL", scale = 3f, search = searchOver(words)).elements.single()

        assertThat(element.left).isEqualTo(30f)
        assertThat(element.top).isEqualTo(15f)
    }

    /** A match that wraps a line comes back as two boxes and is one run. */
    @Test
    fun aMatchSpanningTwoBoxes_isOneRun() {
        val page = PdfTextRuns.build("WRAPPED", scale = 1f) {
            listOf(listOf(PdfTextRuns.Box(0f, 0f, 30f, 20f), PdfTextRuns.Box(0f, 25f, 40f, 45f)))
        }

        assertThat(page.elements.single().bottom).isEqualTo(45f)
    }

    /**
     * **End to end: a bill rebuilt from its text layer reads exactly like the
     * bill laid out directly.** The GST invoice fixture is full of the trap —
     * `5.34` twice, `70.00` inside nothing but `0.00`-shaped substrings
     * everywhere — so any claiming mistake shows up as a changed line or a
     * bill that stops balancing.
     */
    @Test
    fun aBillRebuiltFromItsTextLayer_extractsLikeTheOriginal() {
        val original = ReceiptFixtures.gstTaxInvoice()
        val text = original.elements.joinToString(" ") { it.text }

        val rebuilt = PdfTextRuns.build(text, scale = 1f, search = searchOver(original.elements))
        val fromText = ReceiptExtractor.extract(rebuilt)
        val direct = ReceiptExtractor.extract(original)

        assertThat(fromText.lines).isEqualTo(direct.lines)
        assertThat(fromText.amount).isEqualTo(direct.amount)
        assertThat(Reconciliation.of(fromText.lines, fromText.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    @Test
    fun anEmptyTextLayer_isAnEmptyPage() {
        assertThat(PdfTextRuns.build("  \n ", scale = 1f) { emptyList() }.isEmpty).isTrue()
    }
}
