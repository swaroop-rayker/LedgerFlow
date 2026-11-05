package com.ledgerflow.core.data.statement

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.UnsupportedStatement
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/** The two readers and the kind check, on bytes rather than the system picker (V2-4 Import). */
class StatementFileReadersTest {

    // ── CSV ─────────────────────────────────────────────────────────────────

    @Test
    fun csv_quotedCellsWithCommasQuotesAndLineBreaks() {
        val csv = "Date,Narration,Amount\r\n" +
            "02/10/2026,\"SWIGGY, BANGALORE\",-410.00\r\n" +
            "03/10/2026,\"Said \"\"thanks\"\"\nline two\",85000.00\r\n"

        val rows = CsvTableReader.read(csv.toByteArray())

        assertThat(rows).containsExactly(
            listOf("Date", "Narration", "Amount"),
            listOf("02/10/2026", "SWIGGY, BANGALORE", "-410.00"),
            listOf("03/10/2026", "Said \"thanks\"\nline two", "85000.00"),
        ).inOrder()
    }

    @Test
    fun csv_findsTheDelimiter() {
        assertThat(CsvTableReader.read("a;b;c\n1;2,5;3\n".toByteArray()))
            .containsExactly(listOf("a", "b", "c"), listOf("1", "2,5", "3")).inOrder()
        assertThat(CsvTableReader.read("a\tb\n1\t2\n".toByteArray()))
            .containsExactly(listOf("a", "b"), listOf("1", "2")).inOrder()
        assertThat(CsvTableReader.read("a|b\n1|2\n".toByteArray()))
            .containsExactly(listOf("a", "b"), listOf("1", "2")).inOrder()
    }

    @Test
    fun csv_readsBomsUtf16AndLatin1() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertThat(CsvTableReader.read(bom + "Café,₹410".toByteArray()))
            .containsExactly(listOf("Café", "₹410"))

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\tb\n1\t2".toByteArray(StandardCharsets.UTF_16LE)
        assertThat(CsvTableReader.read(utf16)).containsExactly(listOf("a", "b"), listOf("1", "2")).inOrder()

        val latin1 = "Caf${0xE9.toChar()},410".toByteArray(StandardCharsets.ISO_8859_1)
        assertThat(CsvTableReader.read(latin1)).containsExactly(listOf("Café", "410"))
    }

    @Test
    fun csv_excelsLeadingZeroFormulasAreUnwrapped() {
        assertThat(CsvTableReader.read("=\"0001\",=\"02/10/2026\"".toByteArray()))
            .containsExactly(listOf("0001", "02/10/2026"))
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    private fun xlsx(sheet: String, sharedStrings: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            put(
                "xl/workbook.xml",
                "<workbook xmlns:r=\"rel\"><sheets>" +
                    "<sheet name=\"Statement\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                "<Relationships><Relationship Id=\"rId7\" Target=\"worksheets/sheet3.xml\"/></Relationships>",
            )
            put("xl/sharedStrings.xml", "<sst>" + sharedStrings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>")
            put("xl/worksheets/sheet3.xml", "<worksheet><sheetData>$sheet</sheetData></worksheet>")
        }
        return out.toByteArray()
    }

    @Test
    fun xlsx_readsSharedAndInlineStringsNumbersAndGaps() {
        val sheet = """
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
            <row r="3"><c r="A3"><v>46297</v></c><c r="B3" t="inlineStr"><is><t>Swiggy</t></is></c><c r="C3"><v>-410.5</v></c></row>
            <row r="4"><c r="A4"><v>46298</v></c><c r="C4"><v>123.45000000000002</v></c></row>
        """.trimIndent()

        val rows = XlsxTableReader.read(xlsx(sheet, listOf("Date", "Narration", "Amount")))

        assertThat(rows).containsExactly(
            listOf("Date", "Narration", "Amount"),
            emptyList<String>(),
            listOf("46297", "Swiggy", "-410.5"),
            // A gap inside a row stays a gap; a stored double reads as Excel shows it.
            listOf("46298", "", "123.45"),
        ).inOrder()
    }

    /** A workbook from anywhere: a DOCTYPE is refused, not expanded. */
    @Test
    fun xlsx_withADoctype_isNotRead() {
        val hostile = xlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>", emptyList()).let { bytes ->
            // Rebuild with an entity declaration in the sheet.
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(
                    (
                        "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><worksheet><sheetData>" +
                            "<row r=\"1\"><c r=\"A1\" t=\"str\"><v>&e;</v></c></row></sheetData></worksheet>"
                        ).toByteArray(),
                )
                zip.closeEntry()
            }
            out.toByteArray().also { assertThat(bytes).isNotEmpty() }
        }

        val rows = XlsxTableReader.read(hostile)

        assertThat(rows.orEmpty().flatten().joinToString()).doesNotContain("localhost")
    }

    // ── The kind check, end to end ──────────────────────────────────────────

    private fun open(name: String, bytes: ByteArray) = StatementFiles.open(StatementDocument(name, bytes), "INR")

    @Test
    fun open_aCsvStatement_isReadAndGuessed() {
        val csv = "Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance\n02/10/26,SWIGGY,410.00,,84590.00\n"

        val opened = open("oct.csv", csv.toByteArray()) as StatementOpened.Opened

        assertThat(opened.table.name).isEqualTo("oct.csv")
        assertThat(opened.table.fingerprint).hasLength(16)
        assertThat(opened.guess?.columns?.amounts).isEqualTo(StatementAmounts.Split(moneyOut = 2, moneyIn = 3))
    }

    @Test
    fun open_anXlsxStatement_isRead() {
        val sheet = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\"><v>46297</v></c><c r=\"B2\"><v>-410</v></c></row>"

        val opened = open("oct.xlsx", xlsx(sheet, listOf("Date", "Amount")))

        assertThat((opened as StatementOpened.Opened).guess?.columns?.amounts).isEqualTo(StatementAmounts.Signed(1))
    }

    /** By what the bytes are, not what the name says. */
    @Test
    fun open_refusesOldExcelAndPdf_byTheirBytes_andSaysWhich() {
        val ole = listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).map { it.toByte() }.toByteArray()
        assertThat(open("statement.csv", ole)).isEqualTo(StatementOpened.Unsupported(UnsupportedStatement.OldExcel))
        assertThat(open("statement.xlsx", "%PDF-1.4".toByteArray()))
            .isEqualTo(StatementOpened.Unsupported(UnsupportedStatement.Pdf))
    }

    @Test
    fun open_emptyOrHugeFiles() {
        assertThat(open("e.csv", ByteArray(0))).isEqualTo(StatementOpened.Unreadable)
        assertThat(open("e.csv", "\n\n,,\n".toByteArray())).isEqualTo(StatementOpened.Unreadable)
        assertThat(open("big.csv", ByteArray(StatementFiles.MAX_BYTES + 1))).isEqualTo(StatementOpened.TooLarge)
        // A zip that is not a workbook.
        val notWorkbook = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("hi".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertThat(open("x.xlsx", notWorkbook)).isEqualTo(StatementOpened.Unreadable)
    }
}
