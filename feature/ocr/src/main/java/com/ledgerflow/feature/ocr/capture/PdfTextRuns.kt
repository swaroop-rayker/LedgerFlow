package com.ledgerflow.feature.ocr.capture

import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage

/**
 * A positioned page rebuilt from a PDF's text layer — **exact glyphs, no OCR**.
 *
 * ## Why, and why like this
 *
 * The owner's quick-commerce invoices arrive as digital PDFs. Rasterising one
 * and recognising it throws the document's own text away and buys every
 * recogniser failure there is: on the device, bigbasket's figures came back
 * with Bengali digit lookalikes beside them (BUG22), and Zepto's `52.00` came
 * back `52,00`, which is correctly refused as money and so cost a line its
 * amount. Read from the text layer instead, both invoices extract **exactly**,
 * every amount and quantity, balanced — measured on the device, in 10–80 ms
 * against ~700 ms for recognition.
 *
 * The platform does not hand over positions directly, and the obvious reading
 * of its API is wrong. `PdfRenderer.Page.getTextContents()` returned, on the
 * device, **one** content object per page holding all its text in
 * content-stream order with **no bounds at all** — columns interleaved, useless
 * to a pipeline that is entirely geometry. What *does* return bounds is
 * `searchText`, once per match. So the page is rebuilt one token at a time:
 * every distinct whitespace-separated token is searched, and each match becomes
 * a run at the match's box.
 *
 * **Longest token first, and a match inside an already-claimed box is
 * dropped.** Search is substring search: `0.00` also matches inside `50.00`
 * and `10.00`, and `1` matches inside nearly everything. Claiming boxes for
 * longer tokens first means a shorter token's hits inside a longer word fall
 * away, while its genuine standalone occurrences — which sit in boxes nobody
 * claimed — survive.
 *
 * This is pure over [search] so the claiming rule is a JVM test; the Android
 * side is [PdfTextLayer].
 */
internal object PdfTextRuns {

    /** A match's box, in whatever units [build]'s caller works in. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

        fun union(other: Box): Box = Box(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom),
        )
    }

    /**
     * [text] is the page's text layer; [search] returns, for one token, each
     * match as the list of boxes it spans (more than one when a match wraps).
     * [scale] multiplies every coordinate, so runs line up with the rendered
     * bitmap that is stored as the attachment.
     */
    fun build(text: String, scale: Float, search: (String) -> List<List<Box>>): RecognizedPage {
        val tokens = text.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            // `distinct` keeps first-seen order, so among equal lengths the
            // token the page printed first claims first -- deterministic, and
            // the one sensible tie-break without reading glyphs back.
            .distinct()
            .sortedByDescending { it.length }

        val claimed = mutableListOf<Box>()
        val elements = mutableListOf<RecognizedElement>()
        tokens.forEach { token ->
            search(token).forEach { spans ->
                val box = spans.reduceOrNull(Box::union) ?: return@forEach
                if (claimed.none { it.contains(box.centerX, box.centerY) }) {
                    claimed += box
                    elements += RecognizedElement(
                        text = token,
                        left = box.left * scale,
                        top = box.top * scale,
                        right = box.right * scale,
                        bottom = box.bottom * scale,
                    )
                }
            }
        }
        return RecognizedPage(elements)
    }

    private val WHITESPACE = Regex("""\s+""")
}
