package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.Money

/**
 * A statement's rows, read with the chosen columns (V2-4 Import).
 *
 * Rows after the last dated one are the footer, and are not read. Above it, a
 * row with neither a date nor an amount is not a transaction — a blank line,
 * a sub-heading, the bank's footer — and is passed over without a word. A row
 * with one and not the other looks like a transaction that could not be read,
 * and is reported with its line number, so nothing the user might expect to
 * see goes missing silently.
 */
public object StatementReading {

    /** @param limit stop after this many lines: the Columns page previews three, not the whole file. */
    public fun read(
        table: StatementTable,
        columns: StatementColumns,
        baseCurrency: String,
        limit: Int = Int.MAX_VALUE,
    ): List<StatementLine> {
        // After the last row with a date comes the bank's footer: a summary of
        // totals and balances, which has amounts and no date and is not a
        // transaction. When no row has a date at all, every row is read, so a
        // wrong date column shows as "no date" rather than as nothing.
        val first = columns.headerRow + 1
        val last = (table.rows.lastIndex downTo first).firstOrNull { row ->
            StatementDate.parse(table.cell(row, columns.date), columns.dateOrder) != null
        } ?: table.rows.lastIndex
        return (first..last).asSequence()
            .mapNotNull { row -> readRow(table, columns, baseCurrency, row) }
            .take(limit)
            .toList()
    }

    private fun readRow(
        table: StatementTable,
        columns: StatementColumns,
        baseCurrency: String,
        row: Int,
    ): StatementLine? {
        val line = row + 1
        val currency = table.cell(row, columns.currency).uppercase().takeIf { CURRENCY.matches(it) } ?: baseCurrency
        val exponent = CurrencyExponent.of(currency)
        val date = StatementDate.parse(table.cell(row, columns.date), columns.dateOrder)
        val amount = amountOf(table, columns.amounts, row, exponent)

        if (date == null) {
            return if (amount == Amount.Absent) null else StatementLine.Unreadable(line, UnreadableReason.NoDate)
        }
        return when (amount) {
            Amount.Absent -> StatementLine.Unreadable(line, UnreadableReason.NoAmount)
            Amount.Both -> StatementLine.Unreadable(line, UnreadableReason.BothAmounts)
            Amount.Zero -> StatementLine.Unreadable(line, UnreadableReason.ZeroAmount)
            is Amount.Found -> StatementLine.Read(
                line = line,
                date = date,
                amount = Money(amount.minor),
                direction = amount.direction,
                description = table.cell(row, columns.description).replace(SPACES, " ").ifBlank { null },
                currency = currency,
            )
        }
    }

    /** What the amount columns held on one row. */
    private sealed interface Amount {
        data object Absent : Amount
        data object Zero : Amount
        data object Both : Amount
        data class Found(val minor: Long, val direction: ExtractedDirection) : Amount
    }

    private fun amountOf(table: StatementTable, amounts: StatementAmounts, row: Int, exponent: Int): Amount =
        when (amounts) {
            is StatementAmounts.Signed -> signed(table, amounts, row, exponent)
            is StatementAmounts.Split -> split(table, amounts, row, exponent)
        }

    private fun signed(table: StatementTable, amounts: StatementAmounts.Signed, row: Int, exponent: Int): Amount {
        val parsed = StatementAmount.parse(table.cell(row, amounts.column), exponent) ?: return Amount.Absent
        if (parsed.minor == 0L) return Amount.Zero
        val fromColumn = markOf(table.cell(row, amounts.markColumn))
        val direction = parsed.marked
            ?: fromColumn
            ?: if (parsed.minor < 0) amounts.negativeIs else opposite(amounts.negativeIs)
        return Amount.Found(kotlin.math.abs(parsed.minor), direction)
    }

    private fun split(table: StatementTable, amounts: StatementAmounts.Split, row: Int, exponent: Int): Amount {
        val out = StatementAmount.parse(table.cell(row, amounts.moneyOut), exponent)?.minor
        val into = StatementAmount.parse(table.cell(row, amounts.moneyIn), exponent)?.minor
        val outPresent = out != null && out != 0L
        val inPresent = into != null && into != 0L
        return when {
            outPresent && inPresent -> Amount.Both
            outPresent -> Amount.Found(kotlin.math.abs(requireNotNull(out)), ExtractedDirection.DEBIT)
            inPresent -> Amount.Found(kotlin.math.abs(requireNotNull(into)), ExtractedDirection.CREDIT)
            out == 0L || into == 0L -> Amount.Zero
            else -> Amount.Absent
        }
    }

    /** "Dr", "DR", "Debit", "D" and their credit twins. Anything else says nothing. */
    internal fun markOf(text: String): ExtractedDirection? = when (text.trim().lowercase().trimEnd('.')) {
        "dr", "debit", "d", "withdrawal" -> ExtractedDirection.DEBIT
        "cr", "credit", "c", "deposit" -> ExtractedDirection.CREDIT
        else -> null
    }

    private fun opposite(direction: ExtractedDirection): ExtractedDirection = when (direction) {
        ExtractedDirection.DEBIT -> ExtractedDirection.CREDIT
        ExtractedDirection.CREDIT -> ExtractedDirection.DEBIT
        ExtractedDirection.UNKNOWN -> ExtractedDirection.UNKNOWN
    }

    private val CURRENCY = Regex("[A-Z]{3}")
    private val SPACES = Regex("""\s+""")
}
