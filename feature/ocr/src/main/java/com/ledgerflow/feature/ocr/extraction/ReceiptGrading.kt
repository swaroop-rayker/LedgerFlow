package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.domain.ingest.ExtractedLineItem
import com.ledgerflow.core.domain.ledger.ItemNameNormalizer
import com.ledgerflow.core.domain.text.JaroWinkler
import com.ledgerflow.core.model.LineItemKind

/**
 * SPEC.md §12's OCR gate, as a definition rather than a sentence: item
 * **recall and precision, per receipt**, against the owner's transcription.
 *
 * - **The denominator of recall is the human's** — every line the owner
 *   recorded as `ITEM` — and never what the extractor found.
 * - **A hit needs the name *and* the exact total.** Names compare after
 *   [ItemNameNormalizer] by Jaro-Winkler at §5.5's
 *   [JaroWinkler.MERCHANT_THRESHOLD] (§12 names the measure and not a figure;
 *   this is the one shared figure, so the callers cannot drift). **Money gets no
 *   tolerance.**
 * - **Precision's denominator is every `ITEM` the extractor emitted**, so an
 *   extractor that emits every fragment scores its recall and pays for it here.
 * - Each extracted line is matched **at most once**, and an expected line takes
 *   the best-named unmatched line with its exact total — so two lines at the
 *   same price cannot both claim one extraction.
 *
 * Lives beside the extractor, not in a test source set, because the JVM tests
 * pin the definition and the on-device grading test applies it to real
 * receipts; one copy is the point.
 */
internal object ReceiptGrading {

    data class ExpectedItem(val name: String, val totalMinor: Long)

    data class Grade(
        val expected: Int,
        val extracted: Int,
        val hits: Int,
        /** Expected lines no extracted line matched. */
        val missed: List<ExpectedItem>,
        /** Extracted `ITEM` lines that matched nothing the owner recorded. */
        val spurious: List<ExtractedLineItem>,
    ) {
        /** `hits / expected`; 1.0 for a receipt with no expected items. */
        val recall: Double get() = if (expected == 0) 1.0 else hits.toDouble() / expected

        /** `hits / extracted`; 1.0 when nothing was extracted *and* nothing was expected. */
        val precision: Double
            get() = when {
                extracted > 0 -> hits.toDouble() / extracted
                expected == 0 -> 1.0
                else -> 0.0
            }
    }

    /** §12's floors. Provisional below the corpus size §12 names. */
    const val RECALL_FLOOR: Double = 0.90
    const val PRECISION_FLOOR: Double = 0.95

    fun grade(expected: List<ExpectedItem>, extracted: List<ExtractedLineItem>): Grade {
        val items = extracted.filter { it.kind == LineItemKind.ITEM }
        val unmatched = items.toMutableList()
        val missed = mutableListOf<ExpectedItem>()
        var hits = 0
        expected.forEach { want ->
            val wantName = ItemNameNormalizer.normalize(want.name)
            val best = unmatched
                .filter { it.total?.minor == want.totalMinor }
                .map { it to JaroWinkler.similarity(wantName, ItemNameNormalizer.normalize(it.name)) }
                .filter { (_, score) -> score >= JaroWinkler.MERCHANT_THRESHOLD }
                .maxByOrNull { (_, score) -> score }
                ?.first
            if (best == null) {
                missed += want
            } else {
                unmatched.remove(best)
                hits++
            }
        }
        return Grade(expected.size, items.size, hits, missed, unmatched)
    }
}
