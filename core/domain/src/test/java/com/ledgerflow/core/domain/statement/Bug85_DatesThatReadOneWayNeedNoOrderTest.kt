package com.ledgerflow.core.domain.statement

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * BUG85: the Columns page asked "Dates read as" for a spreadsheet whose
 * dates could only be read one way.
 *
 * Found 2026-11-05 importing the sample `.xlsx`: its date cells hold
 * spreadsheet serial numbers, which have no day-month order to get wrong. The
 * guess called the order known only when some date's day was above 12, so a
 * column of serials — or of named months, or of year-first dates — was
 * treated like `03/04/26` and the page asked a question with no wrong answer.
 *
 * The order is asked for only when a date is written all in numbers with the
 * day or the month first, and the column does not say which.
 */
class Bug85_DatesThatReadOneWayNeedNoOrderTest {

    private fun guessFor(vararg dates: String): StatementGuess.Guess = requireNotNull(
        StatementGuess.guess(
            StatementTable(
                name = "statement.xlsx",
                rows = listOf(listOf("Date", "Particulars", "Amount")) +
                    dates.map { listOf(it, "Something", "-410.00") },
                fingerprint = "fp",
            ),
        ),
    )

    @Test
    fun spreadsheetSerials_needNoOrder() {
        assertThat(guessFor("46315", "46317", "46320").dateOrderKnown).isTrue()
    }

    @Test
    fun namedMonths_needNoOrder() {
        assertThat(guessFor("02-Oct-2026", "3 Oct 2026").dateOrderKnown).isTrue()
    }

    @Test
    fun yearFirstDates_needNoOrder() {
        assertThat(guessFor("2026-10-02", "2026-10-03").dateOrderKnown).isTrue()
    }

    /** The question still comes when it has an answer: every value reads both ways. */
    @Test
    fun allNumberDatesThatReadBothWays_stillAsk() {
        assertThat(guessFor("02/10/2026", "03/04/2026").dateOrderKnown).isFalse()
    }

    /** And when one of them settles it, it does not. */
    @Test
    fun allNumberDatesWithADayAboveTwelve_needNoOrder() {
        assertThat(guessFor("02/10/2026", "25/10/2026").dateOrderKnown).isTrue()
    }
}
