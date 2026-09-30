package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import org.junit.Test

/**
 * The workbook as bytes (ADR-0004): unzipped and read back as the XML a
 * spreadsheet application would parse.
 */
class XlsxWorkbookWriterTest {

    @Test
    fun summariesComeFirst_thenEveryTable_inOrder() {
        val files = write(
            pivots = listOf(pivot(XlsxPivots.MONTHLY_TOTALS_SHEET), pivot(XlsxPivots.SPENDING_SHEET)),
            documents = listOf(document("ledger_entry_debit.csv"), document("budget.csv")),
        )

        assertThat(sheetNames(files))
            .containsExactly("Monthly totals", "Spending by month", "ledger_entry_debit", "budget").inOrder()
    }

    @Test
    fun pivotMoney_isAnExactNumber_atTheBaseCurrencysExponent() {
        val sheet = PivotSheet(
            name = "Monthly totals",
            header = listOf("Month", "Spent", "Received"),
            rows = listOf(PivotRow("2026-09", listOf(1_500L, 90_001L))),
        )

        val inr = sheetXml(write(pivots = listOf(sheet), baseExponent = 2), 1)
        assertThat(numberAt(inr, "B2")).isEqualTo("15.00")
        assertThat(numberAt(inr, "C2")).isEqualTo("900.01")

        // A zero-decimal book is not shown as hundredths.
        val jpy = sheetXml(write(pivots = listOf(sheet), baseExponent = 0), 1)
        assertThat(numberAt(jpy, "B2")).isEqualTo("1500")
    }

    @Test
    fun rawMoney_isRecomputedFromTheInteger_inTheRowsOwnCurrency() {
        val entries = CsvDocument(
            fileName = "ledger_entry_debit.csv",
            header = listOf(
                "id", "amount_minor", "amount", "currency",
                "original_amount_minor", "original_amount", "original_currency",
                "fx_rate_micro", "fx_rate",
            ),
            rows = listOf(
                // The CSV's decimal text is ignored: it is always two places.
                listOf("e1", "25500", "255.00", "INR", "4950", "49.50", "USD", "83230000", "83.230000"),
                listOf("e2", "1200", "12.00", "JPY", "1200", "12.00", "JPY", null, null),
            ),
        )

        val xml = sheetXml(write(documents = listOf(entries)), 1)

        assertThat(numberAt(xml, "B2")).isEqualTo("25500") // amount_minor, whole
        assertThat(numberAt(xml, "C2")).isEqualTo("255.00") // amount, INR
        assertThat(numberAt(xml, "F2")).isEqualTo("49.50") // original_amount, in USD
        assertThat(numberAt(xml, "I2")).isEqualTo("83.230000") // fx_rate, micro-units
        assertThat(numberAt(xml, "C3")).isEqualTo("1200") // JPY has no minor unit
    }

    @Test
    fun sheetNames_fitExcelsRules_andNeverCollide() {
        val files = write(
            documents = listOf(
                document("a_table_name_far_longer_than_thirty_one_characters.csv"),
                document("a_table_name_far_longer_than_thirty_one_characters_too.csv"),
                document("odd[name]:with*chars?.csv"),
            ),
        )

        val names = sheetNames(files)
        assertThat(names.all { it.length <= 31 }).isTrue()
        assertThat(names.map { it.lowercase() }.toSet()).hasSize(names.size)
        assertThat(names.last()).isEqualTo("odd_name__with_chars_")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun write(
        pivots: List<PivotSheet> = emptyList(),
        documents: List<CsvDocument> = emptyList(),
        baseExponent: Int = 2,
    ): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { XlsxWorkbookWriter(baseExponent).write(it, pivots, documents) }
        // Read through the central directory, as Excel, LibreOffice and Sheets
        // do. `ZipInputStream` reads entries front to back and rejects the
        // streamed entries fastexcel writes (sizes in a trailing descriptor),
        // which says nothing about whether a spreadsheet can open the file.
        val file = File.createTempFile("export", ".xlsx").apply { deleteOnExit() }
        file.writeBytes(bytes.toByteArray())
        return ZipFile(file).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private fun sheetNames(files: Map<String, String>): List<String> =
        Regex("""<sheet [^>]*name="([^"]+)"""").findAll(files.getValue("xl/workbook.xml"))
            .map { it.groupValues[1].replace("&amp;", "&") }.toList()

    private fun sheetXml(files: Map<String, String>, index: Int): String =
        files.getValue("xl/worksheets/sheet$index.xml")

    /** The numeric value of cell [ref], or null if it is absent or text. */
    private fun numberAt(sheetXml: String, ref: String): String? {
        val cell = Regex("""<c r="$ref"([^>]*)>(.*?)</c>""").find(sheetXml) ?: return null
        if ("t=\"s\"" in cell.groupValues[1] || "t=\"inlineStr\"" in cell.groupValues[1]) return null
        return Regex("""<v>([^<]*)</v>""").find(cell.groupValues[2])?.groupValues?.get(1)
    }

    private fun pivot(name: String) =
        PivotSheet(name, listOf("Month", "Spent"), listOf(PivotRow("2026-09", listOf(1L))))

    private fun document(fileName: String) = CsvDocument(fileName, listOf("id"), listOf(listOf("x")))
}
