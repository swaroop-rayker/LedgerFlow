package com.ledgerflow.core.domain.statement

/**
 * Which column is which, guessed from the file (V2-4 Import, option A's
 * Columns page shows the guess and lets the user correct it).
 *
 * **Headings first.** A bank statement has preamble lines — the bank's name,
 * the account, the period — then a heading row, then the transactions. The
 * heading row is the first of the opening rows whose cells name a date and an
 * amount ("Txn Date", "Narration", "Withdrawal Amt.", "Deposit Amt."). A
 * balance column is never taken for an amount: it is the one column every
 * statement has that looks exactly like money and is not a transaction.
 *
 * **Then content**, for a file with no headings: the column most of whose
 * cells read as dates, the first other column most of whose cells read as
 * amounts, and the wordiest of the rest as the description.
 */
public object StatementGuess {

    /**
     * The guess, and whether the date order is settled: by the dates
     * themselves (a day above 12), or because none of them depends on it
     * (serials, named months, year first: BUG85). Only when neither holds
     * does the Columns page ask.
     */
    public data class Guess(val columns: StatementColumns, val dateOrderKnown: Boolean)

    /** Null when nothing in the file reads as a date and an amount. */
    public fun guess(table: StatementTable): Guess? {
        val byHeadings = (0 until minOf(table.rows.size, HEADER_SEARCH)).firstNotNullOfOrNull { headingsAt(table, it) }
        val columns = byHeadings ?: byContent(table) ?: return null
        val samples = (columns.headerRow + 1 until table.rows.size).map { table.cell(it, columns.date) }
        val order = StatementDate.orderOf(samples)
        // Asked only when some date needs it and none of them settles it (BUG85).
        val known = order != null || samples.none(StatementDate::dependsOnOrder)
        return Guess(columns.copy(dateOrder = order ?: StatementDateOrder.DayFirst), dateOrderKnown = known)
    }

    private fun headingsAt(table: StatementTable, row: Int): StatementColumns? {
        val headings = table.rows[row].map { it.trim().lowercase() }
        fun first(words: Regex, except: Regex? = null): Int? = headings.indexOfFirst { heading ->
            heading.isNotEmpty() && words.containsMatchIn(heading) && !BALANCE.containsMatchIn(heading) &&
                (except == null || !except.containsMatchIn(heading))
        }.takeIf { it >= 0 }

        val date = first(DATE, except = VALUE_DATE) ?: first(DATE)
        val moneyOut = first(MONEY_OUT, except = MARK_HEADING)
        val moneyIn = first(MONEY_IN, except = MARK_HEADING)
        val amount = first(AMOUNT)
        val amounts = when {
            moneyOut != null && moneyIn != null && moneyOut != moneyIn -> StatementAmounts.Split(moneyOut, moneyIn)
            amount != null -> StatementAmounts.Signed(amount, markColumn = first(MARK_HEADING))
            else -> null
        }
        return if (date == null || amounts == null) {
            null
        } else {
            StatementColumns(
                headerRow = row,
                date = date,
                description = first(DESCRIPTION),
                amounts = amounts,
                currency = first(CURRENCY),
            )
        }
    }

    private fun byContent(table: StatementTable): StatementColumns? {
        val rows = table.rows.indices
        val width = table.columnCount
        fun share(column: Int, reads: (String) -> Boolean): Int = rows.count { reads(table.cell(it, column)) }

        val isDate = { text: String -> StatementDate.parse(text, StatementDateOrder.DayFirst) != null }
        val nonBlankRows = table.rows.count { cells -> cells.any { it.isNotBlank() } }
        // A date column must hold dates in at least half the rows that hold anything.
        val dateColumn = (0 until width).maxByOrNull { share(it, isDate) }
            ?.takeIf { share(it, isDate) * 2 >= nonBlankRows && nonBlankRows > 0 }
            ?: return null
        val datesFound = share(dateColumn, isDate)

        val amountColumn = (0 until width).filter { it != dateColumn }.firstOrNull { column ->
            share(column) { StatementAmount.parse(it, exponent = 2) != null } * 2 >= datesFound
        } ?: return null
        val description = (0 until width).filter { it != dateColumn && it != amountColumn }
            .maxByOrNull { column -> rows.sumOf { table.cell(it, column).count(Char::isLetter) } }

        // The first row is a heading if it is the only one that is not a date.
        val firstIsHeading = StatementDate.parse(table.cell(0, dateColumn), StatementDateOrder.DayFirst) == null
        return StatementColumns(
            headerRow = if (firstIsHeading) 0 else -1,
            date = dateColumn,
            description = description,
            amounts = StatementAmounts.Signed(amountColumn),
        )
    }

    /** How far down a statement's heading row may be: past the bank's preamble, not into the data. */
    private const val HEADER_SEARCH = 40

    private val DATE = Regex("""date""")
    private val VALUE_DATE = Regex("""value""")
    private val BALANCE = Regex("""balance|bal\.?$""")
    private val MONEY_OUT = Regex("""withdraw|debit|paid out|money out|spent|outflow|^dr\b""")
    private val MONEY_IN = Regex("""deposit|credit|paid in|money in|received|inflow|^cr\b""")
    private val AMOUNT = Regex("""amount|amt|value \(|^value$|sum""")
    private val MARK_HEADING = Regex(
        """^(dr\s*/\s*cr|cr\s*/\s*dr|debit\s*/\s*credit|type|transaction type|txn type)$""",
    )
    private val DESCRIPTION = Regex("""narration|description|particulars|details|remarks|merchant|payee|memo|name""")
    private val CURRENCY = Regex("""currency|ccy""")
}
