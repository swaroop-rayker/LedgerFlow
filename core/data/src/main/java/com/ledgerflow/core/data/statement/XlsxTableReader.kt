package com.ledgerflow.core.data.statement

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * The first sheet of an `.xlsx` workbook, read into rows of cells (V2-4 Import).
 *
 * **No library** (ADR-0004's reasoning, read side): an `.xlsx` is a zip of XML,
 * and the platform has both. Shared strings, inline strings and numbers are
 * read; styles are not, so a date cell arrives as the spreadsheet's serial day
 * number, which `StatementDate` reads.
 *
 * **A number is written as Excel shows it**: the stored double to 15
 * significant digits, which is Excel's own display precision, through
 * `BigDecimal` — so `1240.5` stays `1240.5` and a stored `123.45000000000002`
 * becomes the `123.45` the user sees in the cell. No floating point is used.
 *
 * Hardened as a reader of files from anywhere: no DTDs or external entities,
 * and a cap on how much the zip may inflate to.
 */
internal object XlsxTableReader {

    /** Null when the bytes are not a workbook this can read. */
    fun read(bytes: ByteArray): List<List<String>>? = runCatching {
        val parts = unzip(bytes)
        val sheet = parts?.let(::firstSheet)?.let(parts::get)
        sheet?.let { cells(it, parts[SHARED_STRINGS]?.let(::sharedStrings).orEmpty()) }
    }.getOrNull()

    /** The workbook's XML parts, or null when they inflate past [MAX_INFLATED]. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray>? {
        val parts = mutableMapOf<String, ByteArray>()
        val budget = longArrayOf(MAX_INFLATED)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.filter { isWorkbookPart(it.name) }.forEach { entry ->
                parts[entry.name] = readEntry(zip, budget) ?: return null
            }
        }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun isWorkbookPart(name: String): Boolean =
        name.startsWith("xl/") && (name.endsWith(".xml") || name.endsWith(".rels"))

    /** One entry, charged against [budget]; null once the budget is spent (a zip bomb). */
    private fun readEntry(zip: ZipInputStream, budget: LongArray): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var read = zip.read(buffer)
        while (read >= 0) {
            budget[0] -= read
            if (budget[0] < 0) return null
            out.write(buffer, 0, read)
            read = zip.read(buffer)
        }
        return out.toByteArray()
    }

    /** The workbook's first sheet, by its relationship; failing that, the lowest-numbered sheet file. */
    private fun firstSheet(parts: Map<String, ByteArray>): String? {
        var firstId: String? = null
        parts[WORKBOOK]?.let { xml ->
            parse(xml) { name, attributes ->
                if (name == "sheet" && firstId == null) firstId = attributes.value("r:id")
            }
        }
        var target: String? = null
        parts[WORKBOOK_RELS]?.takeIf { firstId != null }?.let { xml ->
            parse(xml) { name, attributes ->
                if (name == "Relationship" && attributes.value("Id") == firstId) target = attributes.value("Target")
            }
        }
        val resolved = target?.removePrefix("/")?.let { if (it.startsWith("xl/")) it else "xl/$it" }
        return resolved?.takeIf { it in parts }
            ?: parts.keys.filter { SHEET_FILE.matches(it) }.minByOrNull(::sheetNumber)
    }

    private fun sheetNumber(name: String): Int = SHEET_FILE.find(name)?.groupValues?.get(1)?.toInt() ?: 0

    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        parseText(
            xml,
            startWith = { name, _ ->
                when (name) {
                    "si" -> current.setLength(0)
                    "t" -> inText = true
                }
            },
            end = { name ->
                when (name) {
                    "t" -> inText = false
                    "si" -> strings += current.toString()
                }
            },
            text = { chars -> if (inText) current.append(chars) },
        )
        return strings
    }

    private fun cells(xml: ByteArray, strings: List<String>): List<List<String>> {
        val sheet = SheetCells(strings)
        parseText(xml, startWith = sheet::start, end = sheet::end, text = sheet::text)
        return sheet.rows()
    }

    /** One sheet's cells, by row and column, as SAX hands them over. */
    private class SheetCells(private val strings: List<String>) {
        private val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        private var rowIndex = -1
        private var column = 0
        private var type: String? = null
        private val value = StringBuilder()
        private var inValue = false

        fun start(name: String, attributes: Attributes) {
            when (name) {
                "row" -> rowIndex = attributes.value("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                "c" -> {
                    column = attributes.value("r")?.let(::columnOf) ?: (rows[rowIndex]?.size ?: 0)
                    type = attributes.value("t")
                    value.setLength(0)
                }
                "v", "t" -> inValue = true
            }
        }

        fun end(name: String) {
            when (name) {
                "v", "t" -> inValue = false
                "c" -> {
                    val text = textOf(type, value.toString(), strings)
                    if (rowIndex >= 0 && text.isNotEmpty()) rows.getOrPut(rowIndex) { mutableMapOf() }[column] = text
                }
            }
        }

        fun text(chars: String) {
            if (inValue) value.append(chars)
        }

        /** Every row from the first to the last, gaps kept, so line numbers match the sheet's. */
        fun rows(): List<List<String>> {
            if (rows.isEmpty()) return emptyList()
            return (0..minOf(rows.lastKey(), CsvTableReader.MAX_ROWS - 1)).map { index ->
                val cells = rows[index].orEmpty()
                val width = (cells.keys.maxOrNull() ?: -1) + 1
                List(width) { cells[it].orEmpty() }
            }
        }
    }

    private fun textOf(type: String?, raw: String, strings: List<String>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
        "inlineStr", "str", "e" -> raw
        "b" -> if (raw == "1") "TRUE" else "FALSE"
        else -> number(raw)
    }

    /** "AB12" → 27. */
    private fun columnOf(reference: String): Int =
        reference.takeWhile(Char::isLetter).uppercase().fold(0) { acc, char -> acc * ALPHABET + (char - 'A' + 1) } - 1

    private fun number(raw: String): String = runCatching {
        BigDecimal(raw.trim()).round(MathContext(DISPLAY_DIGITS)).stripTrailingZeros().toPlainString()
    }.getOrElse { raw }

    private fun parse(xml: ByteArray, onStart: (String, Attributes) -> Unit) = parseText(xml, startWith = onStart)

    private fun parseText(
        xml: ByteArray,
        startWith: (String, Attributes) -> Unit = { _, _ -> },
        end: (String) -> Unit = {},
        text: (String) -> Unit = {},
    ) {
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(
            InputSource(ByteArrayInputStream(xml)),
            object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) =
                    startWith(local(qName, localName), attributes)

                override fun endElement(uri: String?, localName: String?, qName: String) = end(local(qName, localName))

                override fun characters(ch: CharArray, start: Int, length: Int) = text(String(ch, start, length))
            },
        )
    }

    /** "x:c" and "c" alike: some writers prefix the spreadsheet namespace. */
    private fun local(qName: String, localName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.substringAfter(':')

    private fun Attributes.value(name: String): String? =
        getValue(name) ?: (0 until length).firstOrNull { index ->
            getQName(index).substringAfter(':') == name.substringAfter(':')
        }?.let { getValue(it) }

    private const val WORKBOOK = "xl/workbook.xml"
    private const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private val SHEET_FILE = Regex("""xl/worksheets/sheet(\d+)\.xml""")
    private const val BUFFER = 8 * 1024
    private const val MAX_INFLATED = 64L * 1024 * 1024
    private const val ALPHABET = 26
    private const val DISPLAY_DIGITS = 15
}
