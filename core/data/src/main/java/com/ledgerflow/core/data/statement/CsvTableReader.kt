package com.ledgerflow.core.data.statement

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A delimited text file, read into rows of cells (V2-4 Import).
 *
 * What banks and spreadsheets actually write: commas, semicolons (a European
 * spreadsheet's CSV), tabs (Excel's "Unicode text", which is also UTF-16) or
 * pipes, chosen by which one splits the opening lines most consistently;
 * quoted cells with doubled quotes and line breaks inside; UTF-8 with or
 * without a byte-order mark, UTF-16, and Latin-1 for an old export that is
 * neither; and Excel's `="0001"` cells, written that way to stop a
 * spreadsheet eating leading zeros.
 */
internal object CsvTableReader {

    fun read(bytes: ByteArray): List<List<String>> {
        val text = decode(bytes)
        val delimiter = delimiterOf(text)
        return parse(text, delimiter).map { row -> row.map(::unwrapFormula) }.dropLastWhile { it.all(String::isBlank) }
    }

    private fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(UTF8_BOM) -> String(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, StandardCharsets.UTF_8)
        bytes.startsWith(UTF16LE_BOM) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        bytes.startsWith(UTF16BE_BOM) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        else -> strictUtf8(bytes) ?: String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** The delimiter that splits the most opening lines into the same number of cells. */
    private fun delimiterOf(text: String): Char {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(SAMPLE_LINES).toList()
        return DELIMITERS.maxByOrNull { delimiter ->
            val counts = lines.map { line -> countOutsideQuotes(line, delimiter) }.filter { it > 0 }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            (common?.value ?: 0) * CONSISTENCY_WEIGHT + (common?.key ?: 0)
        } ?: ','
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var quoted = false
        return line.count { char ->
            if (char == '"') quoted = !quoted
            char == delimiter && !quoted
        }
    }

    private fun parse(text: String, delimiter: Char): List<List<String>> = Parser(text, delimiter).rows()

    /** RFC 4180, forgivingly: a quote opens a cell only at its start, and `""` inside one is a quote. */
    private class Parser(private val text: String, private val delimiter: Char) {
        private val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val cell = StringBuilder()
        private var quoted = false
        private var index = 0

        fun rows(): List<List<String>> {
            while (index < text.length && rows.size < MAX_ROWS) {
                if (quoted) inQuotes(text[index]) else outside(text[index])
                index++
            }
            if (rows.size < MAX_ROWS && (cell.isNotEmpty() || row.isNotEmpty())) endRow()
            return rows
        }

        private fun inQuotes(char: Char) {
            when {
                char == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = false
                else -> cell.append(char)
            }
        }

        private fun outside(char: Char) {
            when (char) {
                '"' -> if (cell.isBlank()) quoted = true else cell.append(char)
                delimiter -> endCell()
                '\r', '\n' -> {
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    endRow()
                }
                else -> cell.append(char)
            }
        }

        private fun endCell() {
            row += cell.toString()
            cell.setLength(0)
        }

        private fun endRow() {
            endCell()
            rows += row
            row = mutableListOf()
        }
    }

    private fun unwrapFormula(cell: String): String =
        FORMULA.matchEntire(cell.trim())?.groupValues?.get(1) ?: cell

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val DELIMITERS = listOf(',', ';', '\t', '|')
    private val FORMULA = Regex("""="(.*)"""")

    private const val SAMPLE_LINES = 30
    private const val CONSISTENCY_WEIGHT = 1_000

    /** More than any statement an import reads at once; the rest is not read. */
    const val MAX_ROWS = 20_000
}
