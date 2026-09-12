package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import org.junit.Test

/**
 * A receipt photographed on a textured surface.
 *
 * The real bill in the corpus store was shot on woven cloth and came back with
 * 195 runs for about 60 printed lines. Some of that surplus is the recogniser
 * finding "text" in the weave, and the question this answers is whether that
 * costs anything — because the answer decides whether a noise filter is worth
 * building at all.
 */
class NoisyCaptureTest {

    private val clean = ReceiptFixtures.ordinaryBill()

    /** Specks either side of the bill, as a weave misread as glyphs. */
    private fun withTexture(count: Int): RecognizedPage = RecognizedPage(
        clean.elements + (0 until count).flatMap { index ->
            val y = 20f + index * 31f
            listOf(
                RecognizedElement("·", 2f, y, 11f, y + 9f),
                RecognizedElement("~", 14f, y + 7f, 23f, y + 16f),
                RecognizedElement("'", 600f, y + 3f, 608f, y + 11f),
            )
        },
    )

    private fun itemsOf(page: RecognizedPage) =
        ReceiptExtractor.extract(page).lines
            .filter { it.kind == LineItemKind.ITEM }
            .map { it.name }

    @Test
    fun textureDoesNotChangeTheItemsOrTheTotal() {
        listOf(10, 30, 60).forEach { count ->
            val noisy = withTexture(count)
            val extracted = ReceiptExtractor.extract(noisy)

            assertThat(extracted.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
                .isEqualTo(itemsOf(clean))
            assertThat(extracted.amount).isEqualTo(Money(69_446L))
        }
    }

    /**
     * The page scale survives, **through the path production uses**.
     *
     * `medianHeight` counts runs, so it holds only while print outnumbers
     * noise. Measured before the filter existed: sixty rows of speckle against
     * fifty words moved it from 20 px to 9, which widens every row band and
     * every column gutter by the same factor. The filter is what restores it,
     * so the assertion runs where the filter does.
     */
    @Test
    fun textureDoesNotMoveThePageScale() {
        val cleanScale =
            ReceiptGeometry.medianHeight(ReceiptGeometry.contentElements(clean))
        val noisyScale =
            ReceiptGeometry.medianHeight(ReceiptGeometry.contentElements(withTexture(30)))

        assertThat(noisyScale).isEqualTo(cleanScale)
    }

    /** And the speckle is gone rather than merely outvoted. */
    @Test
    fun textureRunsAreDropped() {
        val noisy = withTexture(30)

        assertThat(noisy.elements.size).isGreaterThan(clean.elements.size)
        assertThat(ReceiptGeometry.contentElements(noisy)).hasSize(clean.elements.size)
    }

    /**
     * A run that carries a letter or a digit is never dropped.
     *
     * The filter has no threshold on purpose, and this is the half that keeps
     * it safe: it removes marks that could not have been part of a name or an
     * amount, and nothing else.
     */
    @Test
    fun realRunsAreNeverDropped() {
        assertThat(ReceiptGeometry.contentElements(clean)).hasSize(clean.elements.size)
    }
}
