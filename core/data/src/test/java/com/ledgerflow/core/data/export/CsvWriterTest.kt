package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RFC 4180 escaping (ADR-0017).
 *
 * Exhaustive on purpose, and unit rather than instrumented, because every
 * defect this class can have is a string defect — and string defects are
 * exactly the ones that survive a happy-path integration test. A comma inside a
 * merchant name silently shifts every column after it and the file still opens;
 * nobody notices until a spreadsheet shows an amount in the currency column six
 * months later.
 */
class CsvWriterTest {

    @Test
    fun plainField_isNotQuoted() {
        assertThat(CsvWriter.escape("Zepto")).isEqualTo("Zepto")
    }

    @Test
    fun fieldWithComma_isQuoted() {
        assertThat(CsvWriter.escape("Reliance Fresh, Andheri"))
            .isEqualTo("\"Reliance Fresh, Andheri\"")
    }

    @Test
    fun embeddedQuote_isDoubled() {
        assertThat(CsvWriter.escape("""Bob's "Best" Cafe"""))
            .isEqualTo(""""Bob's ""Best"" Cafe"""")
    }

    /** A note field can hold anything the user typed, newlines included. */
    @Test
    fun embeddedNewline_isQuoted() {
        assertThat(CsvWriter.escape("line one\nline two"))
            .isEqualTo("\"line one\nline two\"")
        assertThat(CsvWriter.escape("line one\r\nline two"))
            .isEqualTo("\"line one\r\nline two\"")
    }

    /**
     * Null and empty must stay distinguishable.
     *
     * The schema means different things by them: `note` absent is not the same
     * fact as a note the user cleared, and `original_currency` being null is
     * what says an entry was not foreign spend. Unquoted-empty for both would
     * collapse the two on the way out.
     */
    @Test
    fun nullAndEmpty_areDistinguishable() {
        assertThat(CsvWriter.escape(null)).isEqualTo("")
        assertThat(CsvWriter.escape("")).isEqualTo("\"\"")
    }

    /**
     * Surrounding whitespace forces quoting, which RFC 4180 does not require.
     *
     * Unquoted, every spreadsheet trims it on import, so a merchant saved as
     * " Zepto" would come back as a different string from the one exported —
     * and the user would have no way to tell that the export had changed their
     * data rather than the app having stored it wrong.
     */
    @Test
    fun surroundingWhitespace_forcesQuoting() {
        assertThat(CsvWriter.escape(" Zepto")).isEqualTo("\" Zepto\"")
        assertThat(CsvWriter.escape("Zepto ")).isEqualTo("\"Zepto \"")
        assertThat(CsvWriter.escape("mid space")).isEqualTo("mid space")
    }

    @Test
    fun unicode_survivesUnquoted() {
        assertThat(CsvWriter.escape("Café ☕ ₹")).isEqualTo("Café ☕ ₹")
    }

    @Test
    fun row_endsWithCrlf() {
        assertThat(CsvWriter.row(listOf("a", "b"))).isEqualTo("a,b\r\n")
    }

    @Test
    fun document_writesHeaderThenRows() {
        val csv = CsvWriter.document(
            header = listOf("id", "name"),
            rows = listOf(listOf("1", "Zepto"), listOf("2", "DMart, Powai")),
        )

        assertThat(csv).isEqualTo(
            "id,name\r\n" +
                "1,Zepto\r\n" +
                "2,\"DMart, Powai\"\r\n",
        )
    }

    @Test
    fun document_withNoRows_isJustTheHeader() {
        assertThat(CsvWriter.document(listOf("id"), emptyList())).isEqualTo("id\r\n")
    }

    /**
     * The draft payload is the worst case in the whole export, and it is real
     * data rather than a contrived string: JSON is nothing but quotes, commas
     * and braces, and it goes into a single field.
     */
    @Test
    fun jsonPayload_survivesAsOneField() {
        val json = """{"amount":"255.00","note":"Chai, samosa","tags":["a","b"]}"""

        val row = CsvWriter.row(listOf("draft-1", json))

        assertThat(row).isEqualTo(
            "draft-1," +
                """"{""amount"":""255.00"",""note"":""Chai, samosa"",""tags"":[""a"",""b""]}"""" +
                "\r\n",
        )
        // Round-trip: unquote and undouble gives back exactly what went in.
        val quoted = row.removePrefix("draft-1,").removeSuffix("\r\n")
        assertThat(quoted.removeSurrounding("\"").replace("\"\"", "\"")).isEqualTo(json)
    }

    @Test
    fun timestamp_isIsoUtc() {
        // 2023-11-14T22:13:20Z
        assertThat(CsvWriter.timestamp(1_700_000_000_000L)).isEqualTo("2023-11-14T22:13:20Z")
        assertThat(CsvWriter.timestamp(null)).isNull()
    }

    @Test
    fun boolean_isZeroOrOne() {
        assertThat(CsvWriter.boolean(true)).isEqualTo("1")
        assertThat(CsvWriter.boolean(false)).isEqualTo("0")
    }
}
