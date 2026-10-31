package com.ledgerflow.core.designsystem.format

import androidx.compose.runtime.Immutable

/**
 * The text LedgerFlow ships for people to read (Settings > About: What's new,
 * Help, the privacy policy, the terms; V2-3), as blocks `LfProse` draws.
 *
 * **A small subset of Markdown, read by hand rather than by a library**
 * (V2-PLAN §3.8, owner 2026-10-31): the texts are ours, written to this
 * subset, and a parser for exactly it is shorter than the dependency it would
 * replace.
 *
 * - `# `, `## `, `### ` at the start of a line: a heading.
 * - `- ` or `* `: a list item; following lines indented by two or more
 *   spaces continue it.
 * - Any other run of non-blank lines: one paragraph, its lines joined by a
 *   space.
 * - Inline: `**bold**` and `` `code` ``. A marker with no partner is kept as
 *   the character it is, never dropped.
 *
 * Nothing else is recognised. Links are plain text: nothing in About opens a
 * browser (Law 6), and an address the user wants they can read and type.
 */
public object ProseMarkdown {

    public fun parse(markdown: String): List<ProseBlock> {
        val blocks = mutableListOf<ProseBlock>()
        val paragraph = mutableListOf<String>()
        var item: MutableList<String>? = null

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += ProseBlock.Paragraph(inline(paragraph.joinToString(" ")))
                paragraph.clear()
            }
            item?.let { blocks += ProseBlock.Bullet(inline(it.joinToString(" "))) }
            item = null
        }

        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                line.isBlank() -> flush()
                heading != null -> {
                    flush()
                    val (marks, text) = heading.destructured
                    blocks += ProseBlock.Heading(level = marks.length, spans = inline(text.trim()))
                }
                bullet != null -> {
                    flush()
                    item = mutableListOf(bullet.groupValues[1].trim())
                }
                item != null && line.startsWith(CONTINUATION) -> item?.add(line.trim())
                else -> {
                    if (item != null) flush()
                    paragraph += line.trim()
                }
            }
        }
        flush()
        return blocks
    }

    /**
     * Text that is not ours to mark up (a licence): one paragraph per run of
     * non-blank lines, nothing else read into it, so a `*` in a legal text
     * stays a `*`.
     */
    public fun plain(text: String): List<ProseBlock> =
        text.lines()
            .joinToString("\n")
            .split(BLANK_LINES)
            .map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
            .map { ProseBlock.Paragraph(listOf(ProseSpan(it))) }

    /** `**bold**` and `` `code` `` inside one block's text. */
    internal fun inline(text: String): List<ProseSpan> {
        val spans = mutableListOf<ProseSpan>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            val marker = when {
                text.startsWith(BOLD, i) -> BOLD
                text[i] == CODE -> CODE.toString()
                else -> null
            }
            val close = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            if (marker != null && close > i + marker.length) {
                if (plain.isNotEmpty()) spans += ProseSpan(plain.toString()).also { plain.clear() }
                val inner = text.substring(i + marker.length, close)
                spans += if (marker == BOLD) ProseSpan(inner, bold = true) else ProseSpan(inner, code = true)
                i = close + marker.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        if (plain.isNotEmpty()) spans += ProseSpan(plain.toString())
        return spans
    }

    private val BLANK_LINES = Regex("""\n\s*\n""")
    private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
    private val BULLET = Regex("^[-*]\\s+(.+)$")
    private const val CONTINUATION = "  "
    private const val BOLD = "**"
    private const val CODE = '`'
}

/** One block of prose, in reading order. */
@Immutable
public sealed interface ProseBlock {
    public val spans: List<ProseSpan>

    /** [level] 1 to 3: `#` to `###`. */
    public data class Heading(val level: Int, override val spans: List<ProseSpan>) : ProseBlock

    public data class Paragraph(override val spans: List<ProseSpan>) : ProseBlock

    public data class Bullet(override val spans: List<ProseSpan>) : ProseBlock
}

/** A run of text with one style: plain, bold, or code (Plex Mono, as figures are). */
@Immutable
public data class ProseSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

/** The block's words without styling: what a test or a screen reader reads. */
public val ProseBlock.plainText: String get() = spans.joinToString("") { it.text }
