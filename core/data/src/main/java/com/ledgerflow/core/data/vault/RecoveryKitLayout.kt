package com.ledgerflow.core.data.vault

/**
 * Where the Recovery Kit PDF's "How to restore" section and its QR code go.
 *
 * Pure geometry, measured through [measure], so the rules below are tested
 * without Android's `Paint` (`RecoveryKitLayoutTest`).
 *
 * **Nothing is drawn under the code** (§8 BUG36). The first layout put the QR in
 * the right margin level with "How to restore" and drew each step as one
 * unwrapped line, so the longer steps ran straight under the code — seen on a
 * printed kit by the owner (2026-09-26) — and nearly off the page. Now every
 * step is wrapped to the text width, with continuation lines under the step's
 * text rather than its number, and the code sits **below** the last step, with
 * its caption above it. Text and code never share a row, whatever the wording.
 */
internal object RecoveryKitLayout {

    /** A line of text at [x], with its baseline at [baseline]. */
    data class Line(val text: String, val x: Float, val baseline: Float)

    /**
     * The laid-out section.
     *
     * @property qrTop the top edge of the code, which is [qrSize] square.
     * @property bottom the lowest point anything reaches.
     */
    data class Section(
        val heading: Line,
        val steps: List<Line>,
        val caption: Line,
        val qrLeft: Float,
        val qrTop: Float,
        val qrSize: Float,
        val bottom: Float,
    )

    /**
     * Lays the section out from [top], the first free baseline below the words.
     *
     * @param measure the rendered width of a string in the body font.
     */
    fun restoreSection(top: Float, steps: List<String>, measure: (String) -> Float): Section {
        val left = MARGIN
        val textWidth = TEXT_WIDTH
        val lineHeight = LINE_HEIGHT
        val qrSize = QR_SIZE
        val heading = Line("How to restore", left, top)
        var y = top + lineHeight
        val lines = mutableListOf<Line>()
        steps.forEachIndexed { index, step ->
            val number = "${index + 1}. "
            val indent = measure(number)
            wrap(step, textWidth - indent, measure).forEachIndexed { lineIndex, part ->
                lines += if (lineIndex == 0) {
                    Line(number + part, left, y)
                } else {
                    Line(part, left + indent, y)
                }
                y += lineHeight
            }
        }
        // A blank line, then the caption, then the code a full line beneath
        // it: a QR code wants about four modules of clear space around it (its
        // quiet zone), and 18 pt is about six of this code's modules. Half a
        // line decoded in tests but left the caption within the quiet zone.
        val caption = Line("Scan to restore", left, y + lineHeight)
        val qrTop = caption.baseline + lineHeight
        return Section(
            heading = heading,
            steps = lines,
            caption = caption,
            qrLeft = left,
            qrTop = qrTop,
            qrSize = qrSize,
            bottom = qrTop + qrSize,
        )
    }

    /**
     * [text] broken at spaces into lines no wider than [maxWidth]. A single
     * word wider than that is kept whole on its own line rather than cut —
     * none of the kit's words come close, and a split word misleads.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && measure(candidate) > maxWidth) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** A4 at 72 dpi, in points. */
    const val PAGE_WIDTH: Int = 595
    const val PAGE_HEIGHT: Int = 842
    const val MARGIN: Float = 48f
    const val TEXT_WIDTH: Float = PAGE_WIDTH - 2 * MARGIN
    const val LINE_HEIGHT: Float = 18f

    /** 132 pt is about 4.7 cm on A4: comfortably read by a phone camera off paper. */
    const val QR_SIZE: Float = 132f
}
