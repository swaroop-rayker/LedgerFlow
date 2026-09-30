package com.ledgerflow.core.data.export

import com.ledgerflow.core.model.CurrencyExponent
import java.io.OutputStream
import java.math.BigDecimal
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet

/**
 * Writes the XLSX export (ADR-0004): summary sheets first, then every table.
 *
 * **Money becomes a spreadsheet number without ever being a `Float` or
 * `Double` here** (Law 3). A cell is `BigDecimal.valueOf(minor, exponent)`,
 * assembled exactly from the `Long`, and carries a number format with the
 * currency's own decimal places — `CurrencyExponent`, not a fixed two, so a
 * JPY or BHD book reads correctly.
 *
 * The raw sheets come from the CSV export's own documents, so they have its
 * columns in its order, and inherit its guards (`ExportCoversEveryTableTest`,
 * `Bug39_EveryCsvRowIsAsWideAsItsHeaderTest`). Integer twins (`_minor`,
 * `_micro`, `_milli`) are written as whole numbers; their readable twin
 * (`amount`, `fx_rate`, `quantity`) is recomputed from the integer at the right
 * scale rather than parsed from the CSV's text. Everything else stays text, so
 * no id or timestamp is reinterpreted by a spreadsheet's type guessing.
 *
 * @param baseExponent decimal places of the vault's base currency.
 */
internal class XlsxWorkbookWriter(private val baseExponent: Int) {

    /** @return how many sheets were written. */
    fun write(output: OutputStream, pivots: List<PivotSheet>, documents: List<CsvDocument>): Int {
        val workbook = Workbook(output, APPLICATION, APPLICATION_VERSION)
        val names = SheetNames()
        pivots.forEach { pivot -> writePivot(workbook.newWorksheet(names.claim(pivot.name)), pivot) }
        documents.forEach { document ->
            writeTable(workbook.newWorksheet(names.claim(document.fileName.removeSuffix(".csv"))), document)
        }
        workbook.finish()
        return pivots.size + documents.size
    }

    private fun writePivot(sheet: Worksheet, pivot: PivotSheet) {
        pivot.header.forEachIndexed { column, title -> headerCell(sheet, 0, column, title) }
        val format = moneyFormat(baseExponent)
        pivot.rows.forEachIndexed { index, row ->
            val r = index + 1
            sheet.value(r, 0, row.label)
            if (row.isTotal) sheet.style(r, 0).bold().set()
            row.cells.forEachIndexed { i, minor ->
                if (minor != null) {
                    val column = i + 1
                    sheet.value(r, column, BigDecimal.valueOf(minor, baseExponent))
                    sheet.style(r, column).format(format).let { if (row.isTotal) it.bold() else it }.set()
                }
            }
        }
    }

    private fun writeTable(sheet: Worksheet, document: CsvDocument) {
        document.header.forEachIndexed { column, title -> headerCell(sheet, 0, column, title) }
        val kinds = columnKinds(document.header)
        document.rows.forEachIndexed { index, row ->
            val r = index + 1
            row.forEachIndexed { column, text ->
                if (text != null) cell(sheet, r, column, text, kinds[column], row)
            }
        }
    }

    private fun cell(sheet: Worksheet, r: Int, column: Int, text: String, kind: ColumnKind, row: List<String?>) {
        when (kind) {
            ColumnKind.Text -> sheet.value(r, column, text)

            ColumnKind.WholeNumber -> text.toLongOrNull()
                ?.let { sheet.value(r, column, it) }
                ?: sheet.value(r, column, text)

            is ColumnKind.Money -> {
                val minor = row.getOrNull(kind.minorColumn)?.toLongOrNull()
                if (minor == null) {
                    sheet.value(r, column, text)
                } else {
                    val exponent = kind.currencyColumn
                        ?.let { row.getOrNull(it) }
                        ?.let(CurrencyExponent::of)
                        ?: baseExponent
                    sheet.value(r, column, BigDecimal.valueOf(minor, exponent))
                    sheet.style(r, column).format(moneyFormat(exponent)).set()
                }
            }

            is ColumnKind.Scaled -> {
                val raw = row.getOrNull(kind.integerColumn)?.toLongOrNull()
                if (raw == null) {
                    sheet.value(r, column, text)
                } else {
                    sheet.value(r, column, BigDecimal.valueOf(raw, kind.scale))
                }
            }
        }
    }

    private fun headerCell(sheet: Worksheet, r: Int, column: Int, title: String) {
        sheet.value(r, column, title)
        sheet.style(r, column).bold().set()
    }

    private sealed interface ColumnKind {
        data object Text : ColumnKind
        data object WholeNumber : ColumnKind

        /** The readable twin of an `X_minor` column, in its currency's exponent. */
        data class Money(val minorColumn: Int, val currencyColumn: Int?) : ColumnKind

        /** The readable twin of an `X_micro` / `X_milli` column. */
        data class Scaled(val integerColumn: Int, val scale: Int) : ColumnKind
    }

    private fun columnKinds(header: List<String>): List<ColumnKind> {
        val index = header.withIndex().associate { it.value to it.index }
        return header.map { title ->
            val minor = index["${title}_minor"]
            val micro = index["${title}_micro"]
            val milli = index["${title}_milli"]
            when {
                INTEGER_SUFFIXES.any { title.endsWith(it) } -> ColumnKind.WholeNumber
                // `original_amount` is in the entry's original currency, not the
                // book's; every other money column is in the row's `currency`,
                // or the base currency when the table has none (line items).
                minor != null -> ColumnKind.Money(
                    minorColumn = minor,
                    currencyColumn = if (title.startsWith(ORIGINAL)) index[ORIGINAL_CURRENCY] else index[CURRENCY],
                )
                micro != null -> ColumnKind.Scaled(micro, MICRO_SCALE)
                milli != null -> ColumnKind.Scaled(milli, MILLI_SCALE)
                else -> ColumnKind.Text
            }
        }
    }

    /** Excel sheet names: at most 31 characters, none of `[]:*?/\`, unique. */
    private class SheetNames {
        private val used = mutableSetOf<String>()

        fun claim(wanted: String): String {
            val clean = wanted.replace(FORBIDDEN, "_").take(MAX_SHEET_NAME)
            var name = clean
            var n = 2
            while (!used.add(name.lowercase())) {
                val suffix = " ($n)"
                name = clean.take(MAX_SHEET_NAME - suffix.length) + suffix
                n++
            }
            return name
        }
    }

    private companion object {
        const val APPLICATION = "LedgerFlow"
        // fastexcel requires `major.minor`; this versions the workbook format,
        // not the app.
        const val APPLICATION_VERSION = "1.0"

        const val MAX_SHEET_NAME = 31
        val FORBIDDEN = Regex("""[\[\]:*?/\\]""")

        val INTEGER_SUFFIXES = listOf("_minor", "_micro", "_milli")
        const val MICRO_SCALE = 6
        const val MILLI_SCALE = 3
        const val ORIGINAL = "original_"
        const val CURRENCY = "currency"
        const val ORIGINAL_CURRENCY = "original_currency"

        fun moneyFormat(exponent: Int): String =
            if (exponent <= 0) "#,##0" else "#,##0." + "0".repeat(exponent)
    }
}
