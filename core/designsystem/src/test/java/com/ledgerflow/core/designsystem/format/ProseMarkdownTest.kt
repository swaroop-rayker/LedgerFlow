package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Markdown subset About's texts are written in (V2-3); nothing outside it is recognised. */
class ProseMarkdownTest {

    @Test
    fun headingsParagraphsAndBullets_inOrder() {
        val blocks = ProseMarkdown.parse(
            """
            # Title

            First line
            second line.

            ## Section
            - one
            - two
              continued
            After the list.
            """.trimIndent(),
        )

        assertThat(blocks.map { it::class.simpleName to it.plainText }).containsExactly(
            "Heading" to "Title",
            "Paragraph" to "First line second line.",
            "Heading" to "Section",
            "Bullet" to "one",
            "Bullet" to "two continued",
            "Paragraph" to "After the list.",
        ).inOrder()
        assertThat((blocks[2] as ProseBlock.Heading).level).isEqualTo(2)
    }

    @Test
    fun boldAndCode_areSpans() {
        val spans = ProseMarkdown.inline("Contact: **owner** at `a@b`.")

        assertThat(spans).containsExactly(
            ProseSpan("Contact: "),
            ProseSpan("owner", bold = true),
            ProseSpan(" at "),
            ProseSpan("a@b", code = true),
            ProseSpan("."),
        ).inOrder()
    }

    /** A marker with no partner is the character it is, never swallowed. */
    @Test
    fun anUnpairedMarker_isKeptAsText() {
        assertThat(ProseMarkdown.inline("2 ** 3 and a ` tick").single())
            .isEqualTo(ProseSpan("2 ** 3 and a ` tick"))
    }

    @Test
    fun windowsLineEndings_andBlankRuns_makeNoEmptyBlocks() {
        val blocks = ProseMarkdown.parse("One\r\n\r\n\r\n\r\nTwo\r\n")

        assertThat(blocks.map { it.plainText }).containsExactly("One", "Two").inOrder()
    }

    /** Links are words, not links: nothing in About opens a browser (Law 6). */
    @Test
    fun aLink_isPlainText() {
        assertThat(ProseMarkdown.parse("See [the site](https://example.com).").single().plainText)
            .isEqualTo("See [the site](https://example.com).")
    }
}
