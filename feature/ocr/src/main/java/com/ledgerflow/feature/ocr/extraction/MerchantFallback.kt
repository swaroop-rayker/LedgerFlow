package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.feature.ocr.recognition.RecognizedPage

/**
 * A digital PDF that names no shop in its text (item 7a, the owner's decision
 * of 2026-09-19).
 *
 * bigbasket's invoice draws its supplier block rather than typing it, so the
 * text layer — exact for every item and total — has no merchant at all. When,
 * and only when, the text-layer read produced none, the page already rendered
 * for the attachment is recognised and its header's legal entity taken
 * ([ReceiptExtractor.legalEntityName]). Everything else still comes from the
 * text layer; recognition is ~0.7 s, paid only on this path.
 *
 * One function, called by the capture screen and by the corpus grading test,
 * so what is graded is what ships.
 */
internal object MerchantFallback {

    suspend fun apply(
        extracted: ExtractedTransaction,
        currency: String,
        recognise: suspend () -> RecognizedPage?,
    ): ExtractedTransaction {
        if (extracted.merchantRaw != null) return extracted
        val name = recognise()?.let { ReceiptExtractor.legalEntityName(it, currency) } ?: return extracted
        return extracted.copy(merchantRaw = name)
    }
}
