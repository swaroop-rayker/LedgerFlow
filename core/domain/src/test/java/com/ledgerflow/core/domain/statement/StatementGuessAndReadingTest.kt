package com.ledgerflow.core.domain.statement

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import org.junit.Test

/**
 * The column guess and the row reading, on the layouts banks actually export.
 * The rows are made up; the shapes — preamble, headings, split money columns,
 * a balance column, a footer — are the real ones.
 */
class StatementGuessAndReadingTest {

    private fun table(vararg rows: List<String>) = StatementTable("statement.csv", rows.toList(), "fp")

    /** A statement with the bank's preamble, split columns and a balance, then a footer. */
    private val splitStatement = table(
        listOf("Example Bank Ltd"),
        listOf("Account No : XXXX1234", "", "Statement period: 01/10/2026 to 31/10/2026"),
        listOf(""),
        listOf("Date", "Narration", "Chq./Ref.No.", "Value Dt", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"),
        listOf("02/10/26", "UPI-SWIGGY-swiggy@axis", "0001", "02/10/26", "410.00", "", "84,590.00"),
        listOf("03/10/26", "NEFT CR-SALARY ACME", "0002", "03/10/26", "", "85,000.00", "1,69,590.00"),
        listOf("25/10/26", "POS CORNER GROCER", "0003", "25/10/26", "1,240.50", "", "1,68,349.50"),
        listOf("27/10/26", "", "", "", "", "", ""),
        listOf("", "*** End of statement ***"),
    )

    @Test
    fun aSplitStatement_isGuessedFromItsHeadings() {
        val guess = requireNotNull(StatementGuess.guess(splitStatement))

        assertThat(guess.columns).isEqualTo(
            StatementColumns(
                headerRow = 3,
                date = 0,
                description = 1,
                amounts = StatementAmounts.Split(moneyOut = 4, moneyIn = 5),
                dateOrder = StatementDateOrder.DayFirst,
            ),
        )
        // 25/10 can only be day first.
        assertThat(guess.dateOrderKnown).isTrue()
    }

    @Test
    fun aSplitStatement_readsEachRowIntoItsBook_andReportsTheOneItCannot() {
        val columns = requireNotNull(StatementGuess.guess(splitStatement)).columns

        val lines = StatementReading.read(splitStatement, columns, baseCurrency = "INR")

        fun read(line: Int, day: Int, minor: Long, direction: ExtractedDirection, description: String) =
            StatementLine.Read(line, LocalDate.of(2026, 10, day), Money(minor), direction, description, "INR")
        assertThat(lines).containsExactly(
            read(5, 2, 41_000, ExtractedDirection.DEBIT, "UPI-SWIGGY-swiggy@axis"),
            read(6, 3, 8_500_000, ExtractedDirection.CREDIT, "NEFT CR-SALARY ACME"),
            read(7, 25, 124_050, ExtractedDirection.DEBIT, "POS CORNER GROCER"),
            // A date and nothing else looks like a transaction: it is reported, not dropped.
            StatementLine.Unreadable(8, UnreadableReason.NoAmount),
            // The footer has neither, and is passed over.
        ).inOrder()
    }

    /** One signed amount column, a Dr/Cr column, ISO dates. */
    @Test
    fun aSignedStatementWithAMarkColumn() {
        val statement = table(
            listOf("Transaction Date", "Transaction Remarks", "Amount (INR)", "Dr / Cr", "Balance (INR)"),
            listOf("2026-10-02", "Swiggy", "410.00", "DR", "84590.00"),
            listOf("2026-10-03", "Salary", "85000.00", "CR", "169590.00"),
        )

        val guess = requireNotNull(StatementGuess.guess(statement))
        assertThat(guess.columns.amounts).isEqualTo(StatementAmounts.Signed(column = 2, markColumn = 3))
        assertThat(guess.columns.dateOrder).isEqualTo(StatementDateOrder.YearFirst)

        val lines = StatementReading.read(statement, guess.columns, "INR").filterIsInstance<StatementLine.Read>()
        assertThat(lines.map { it.direction })
            .containsExactly(ExtractedDirection.DEBIT, ExtractedDirection.CREDIT).inOrder()
    }

    /** A plain spreadsheet with no headings at all: guessed from what the cells hold. */
    @Test
    fun noHeadings_isGuessedFromContent() {
        val statement = table(
            listOf("02/10/2026", "Swiggy", "-410.00"),
            listOf("03/10/2026", "Salary", "85000.00"),
            listOf("25/10/2026", "Corner Grocer", "-1240.50"),
        )

        val columns = requireNotNull(StatementGuess.guess(statement)).columns

        assertThat(columns).isEqualTo(
            StatementColumns(
                headerRow = -1,
                date = 0,
                description = 1,
                amounts = StatementAmounts.Signed(2),
                dateOrder = StatementDateOrder.DayFirst,
            ),
        )
        val read = StatementReading.read(statement, columns, "INR").filterIsInstance<StatementLine.Read>()
        assertThat(read.map { it.direction })
            .containsExactly(ExtractedDirection.DEBIT, ExtractedDirection.CREDIT, ExtractedDirection.DEBIT).inOrder()
    }

    /** "Negative is money in": the choice the Columns page offers, for exports that sign the other way. */
    @Test
    fun negativeCanMeanMoneyIn() {
        val statement = table(listOf("02/10/2026", "Refund", "-410.00"))
        val columns = StatementColumns(-1, 0, 1, StatementAmounts.Signed(2, negativeIs = ExtractedDirection.CREDIT))

        val read = StatementReading.read(statement, columns, "INR").single() as StatementLine.Read

        assertThat(read.direction).isEqualTo(ExtractedDirection.CREDIT)
        assertThat(read.amount).isEqualTo(Money(41_000))
    }

    @Test
    fun aCurrencyColumn_keepsEachRowsCurrency_andReadsItsDecimals() {
        val statement = table(
            listOf("Date", "Description", "Amount", "Currency"),
            listOf("02/10/2026", "Hotel", "-120.50", "USD"),
            listOf("02/10/2026", "Taxi", "-1,500", "JPY"),
            listOf("03/10/2026", "Lunch", "-410", ""),
        )
        val columns = requireNotNull(StatementGuess.guess(statement)).columns
        assertThat(columns.currency).isEqualTo(3)

        val read = StatementReading.read(statement, columns, "INR").filterIsInstance<StatementLine.Read>()

        assertThat(read.map { it.currency to it.amount }).containsExactly(
            "USD" to Money(12_050),
            "JPY" to Money(1_500),
            "INR" to Money(41_000),
        ).inOrder()
    }

    @Test
    fun unreadableRows_sayWhy() {
        val statement = table(
            listOf("Date", "Description", "Withdrawal", "Deposit"),
            listOf("someday", "No date", "410.00", ""),
            listOf("02/10/2026", "Both", "410.00", "410.00"),
            listOf("02/10/2026", "Nothing", "0.00", "0.00"),
        )
        val columns = requireNotNull(StatementGuess.guess(statement)).columns

        assertThat(StatementReading.read(statement, columns, "INR")).containsExactly(
            StatementLine.Unreadable(2, UnreadableReason.NoDate),
            StatementLine.Unreadable(3, UnreadableReason.BothAmounts),
            StatementLine.Unreadable(4, UnreadableReason.ZeroAmount),
        ).inOrder()
    }

    @Test
    fun aFileWithNoDatesAndAmounts_hasNoGuess() {
        assertThat(StatementGuess.guess(table(listOf("Name", "Phone"), listOf("Asha", "98450")))).isNull()
    }

    /** The check's figures keep the books apart and leave other currencies out of the totals. */
    @Test
    fun theCheck_countsAndTotalsEachBookOnItsOwn() {
        fun row(direction: ExtractedDirection, minor: Long, currency: String = "INR", there: Boolean = false) =
            CheckedRow(
                StatementLine.Read(1, LocalDate.of(2026, 10, 2), Money(minor), direction, null, currency),
                alreadyThere = there,
            )
        val check = StatementCheck(
            baseCurrency = "INR",
            rows = listOf(
                row(ExtractedDirection.DEBIT, 41_000),
                row(ExtractedDirection.DEBIT, 10_000, currency = "USD"),
                row(ExtractedDirection.DEBIT, 99_999, there = true),
                row(ExtractedDirection.CREDIT, 8_500_000),
            ),
            unreadable = emptyList(),
        )

        assertThat(check.expenses).isEqualTo(2)
        assertThat(check.expensesTotal).isEqualTo(Money(41_000))
        assertThat(check.income).isEqualTo(1)
        assertThat(check.incomeTotal).isEqualTo(Money(8_500_000))
        assertThat(check.otherCurrency).isEqualTo(1)
        assertThat(check.alreadyThere).isEqualTo(1)
        assertThat(check.toAdd).isEqualTo(3)
    }
}
