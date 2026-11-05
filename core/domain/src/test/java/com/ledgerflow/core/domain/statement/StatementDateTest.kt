package com.ledgerflow.core.domain.statement

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import org.junit.Test

class StatementDateTest {

    private val oct2 = LocalDate.of(2026, 10, 2)

    @Test
    fun theUsualShapes_dayFirst() {
        listOf(
            "02/10/2026", "2/10/2026", "02-10-2026", "02.10.2026", "02/10/26",
            "2026-10-02", "2026/10/02", "20261002",
            "02-Oct-2026", "2 Oct 2026", "02 October 2026", "Oct 2, 2026", "02-Oct-26", "2-oct-26",
            "02/10/2026 14:05", "2026-10-02T14:05:00Z", "02/10/2026 2:05 PM",
        ).forEach { assertWithMessage(it).that(StatementDate.parse(it, StatementDateOrder.DayFirst)).isEqualTo(oct2) }
    }

    @Test
    fun monthFirst_readsAmericanDates() {
        assertThat(StatementDate.parse("10/02/2026", StatementDateOrder.MonthFirst)).isEqualTo(oct2)
        // A four-digit year first is a year whatever the order.
        assertThat(StatementDate.parse("2026-10-02", StatementDateOrder.MonthFirst)).isEqualTo(oct2)
        // A named month needs no order.
        assertThat(StatementDate.parse("2 Oct 2026", StatementDateOrder.MonthFirst)).isEqualTo(oct2)
    }

    /** An XLSX date cell holds the spreadsheet's serial day number. */
    @Test
    fun spreadsheetSerials() {
        assertThat(StatementDate.parse("46297", StatementDateOrder.DayFirst)).isEqualTo(oct2)
        assertThat(StatementDate.parse("46297.5", StatementDateOrder.DayFirst)).isEqualTo(oct2)
        // An amount is not a date.
        assertThat(StatementDate.parse("410", StatementDateOrder.DayFirst)).isNull()
        assertThat(StatementDate.parse("12500", StatementDateOrder.DayFirst)).isNull()
    }

    @Test
    fun notDates() {
        listOf("", "Opening balance", "31/02/2026", "13/13/2026", "410.00", "UPI/123/456").forEach {
            assertWithMessage(it).that(StatementDate.parse(it, StatementDateOrder.DayFirst)).isNull()
        }
    }

    @Test
    fun orderOf_aColumn() {
        assertThat(StatementDate.orderOf(listOf("02/10/2026", "25/10/2026"))).isEqualTo(StatementDateOrder.DayFirst)
        assertThat(StatementDate.orderOf(listOf("10/02/2026", "10/25/2026"))).isEqualTo(StatementDateOrder.MonthFirst)
        assertThat(StatementDate.orderOf(listOf("2026-10-02"))).isEqualTo(StatementDateOrder.YearFirst)
        // Every value reads both ways: the user is asked.
        assertThat(StatementDate.orderOf(listOf("02/10/2026", "03/04/2026"))).isNull()
        // Named months say nothing about the order of numbers.
        assertThat(StatementDate.orderOf(listOf("02-Oct-2026"))).isNull()
    }
}
