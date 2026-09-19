package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedLineItem
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.extraction.ReceiptGrading.ExpectedItem
import org.junit.Test

/** SPEC.md §12's metric, pinned: what counts as a hit, and what each ratio divides by. */
class ReceiptGradingTest {

    private fun item(name: String, minor: Long?, kind: LineItemKind = LineItemKind.ITEM) =
        ExtractedLineItem(name = name, kind = kind, total = minor?.let(::Money))

    @Test
    fun aPerfectRead_isFullRecallAndPrecision() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("TOMATO 1KG", 4_000), ExpectedItem("MILK 500ML", 2_800)),
            listOf(item("TOMATO 1KG", 4_000), item("MILK 500ML", 2_800)),
        )
        assertThat(grade.recall).isEqualTo(1.0)
        assertThat(grade.precision).isEqualTo(1.0)
    }

    /** §12's own example: OCR reads `TOMATO` as `TOMAT0`, and that is still the item. */
    @Test
    fun aMisreadGlyph_isStillAHit() {
        val grade = ReceiptGrading.grade(listOf(ExpectedItem("TOMATO 1KG", 4_000)), listOf(item("TOMAT0 1KG", 4_000)))
        assertThat(grade.hits).isEqualTo(1)
    }

    /**
     * Names compare after `ItemNameNormalizer`: the owner transcribes what the
     * page prints, the extractor emits what it read, and case and punctuation
     * differ between the two without the item differing.
     */
    @Test
    fun caseAndPunctuation_doNotMakeAMiss() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("Lay's American Cream & Onion | Potato Chips (52 g)", 5_000)),
            listOf(item("LAYS AMERICAN CREAM ONION POTATO CHIPS 52 G", 5_000)),
        )
        assertThat(grade.hits).isEqualTo(1)
    }

    /** Money gets no tolerance: the right name one paisa off is a miss. */
    @Test
    fun aTotalOffByOnePaisa_isAMiss() {
        val grade = ReceiptGrading.grade(listOf(ExpectedItem("TOMATO 1KG", 4_000)), listOf(item("TOMATO 1KG", 4_001)))
        assertThat(grade.hits).isEqualTo(0)
        assertThat(grade.missed).hasSize(1)
        assertThat(grade.spurious).hasSize(1)
    }

    @Test
    fun theRightTotalUnderAnotherName_isAMiss() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("TOMATO 1KG", 4_000)),
            listOf(item("DETERGENT BAR", 4_000)),
        )
        assertThat(grade.hits).isEqualTo(0)
    }

    /** Emitting every fragment buys recall and pays for it in precision. */
    @Test
    fun spuriousLines_costPrecision_notRecall() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("TOMATO 1KG", 4_000)),
            listOf(item("TOMATO 1KG", 4_000), item("GSTIN 29AAJCG0980D1ZK", 58_002), item("THANK YOU", null)),
        )
        assertThat(grade.recall).isEqualTo(1.0)
        assertThat(grade.precision).isWithin(1e-9).of(1.0 / 3)
    }

    /** Recall's denominator is the owner's lines: what was not found counts against it. */
    @Test
    fun aMissedLine_costsRecall() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("TOMATO 1KG", 4_000), ExpectedItem("MILK 500ML", 2_800)),
            listOf(item("TOMATO 1KG", 4_000)),
        )
        assertThat(grade.recall).isEqualTo(0.5)
        assertThat(grade.precision).isEqualTo(1.0)
    }

    /** One extraction cannot satisfy two expected lines at the same price. */
    @Test
    fun eachExtractedLine_matchesAtMostOnce() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("BUN 200G", 4_018), ExpectedItem("BUN 200G", 4_018)),
            listOf(item("BUN 200G", 4_018)),
        )
        assertThat(grade.hits).isEqualTo(1)
    }

    /** Only ITEM lines are graded; a tax row is neither a hit nor a spurious item. */
    @Test
    fun nonItemLines_areNotGraded() {
        val grade = ReceiptGrading.grade(
            listOf(ExpectedItem("TOMATO 1KG", 4_000)),
            listOf(item("TOMATO 1KG", 4_000), item("CGST 2.5%", 100, LineItemKind.TAX)),
        )
        assertThat(grade.extracted).isEqualTo(1)
        assertThat(grade.precision).isEqualTo(1.0)
    }
}
