package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

/** The XLSX export's summary sheets (ADR-0004), including their Law 2 guard. */
class XlsxPivotsTest {

    @Test
    fun monthlyTotals_areTwoFiguresPerMonth_withNoNetColumn() {
        val sheet = XlsxPivots.monthlyTotals(
            debit = listOf(debit(SEP_3, "food", 1_000), debit(SEP_20, "rent", 500)),
            credit = listOf(credit(SEP_3, "salary", 9_000)),
        )

        // Law 2: exactly these three columns. "Received minus spent" is the
        // netted figure the app never shows, spreadsheet included.
        assertThat(sheet.header).containsExactly("Month", "Spent", "Received").inOrder()
        assertThat(sheet.rows).containsExactly(
            PivotRow("2026-09", listOf(1_500L, 9_000L)),
            PivotRow("Total", listOf(1_500L, 9_000L), isTotal = true),
        ).inOrder()
    }

    @Test
    fun monthlyTotals_showAMonthEitherBookHas_withAZeroForTheOther() {
        val sheet = XlsxPivots.monthlyTotals(
            debit = listOf(debit(SEP_3, "food", 700)),
            credit = listOf(credit(OCT_1, "salary", 9_000)),
        )

        assertThat(sheet.rows.map { it.label }).containsExactly("2026-09", "2026-10", "Total").inOrder()
        assertThat(sheet.rows[0].cells).containsExactly(700L, 0L).inOrder()
        assertThat(sheet.rows[1].cells).containsExactly(0L, 9_000L).inOrder()
    }

    @Test
    fun byCategoryAndMonth_ranksCategoriesByTotal_andLeavesEmptyMonthsBlank() {
        val sheet = XlsxPivots.byCategoryAndMonth(
            name = XlsxPivots.SPENDING_SHEET,
            ledger = LedgerType.DEBIT,
            buckets = listOf(
                debit(SEP_3, "food", 300),
                debit(SEP_20, "food", 200),
                debit(OCT_1, "rent", 2_000),
                debit(OCT_1, "", 50),
            ),
            categoryNames = mapOf("food" to "Food", "rent" to "Rent"),
        )

        assertThat(sheet.header).containsExactly("Category", "2026-09", "2026-10", "Total").inOrder()
        assertThat(sheet.rows).containsExactly(
            PivotRow("Rent", listOf(null, 2_000L, 2_000L)),
            PivotRow("Food", listOf(500L, null, 500L)),
            // An entry filed under nothing is its own row, not dropped.
            PivotRow("Unfiled", listOf(null, 50L, 50L)),
            PivotRow("Total", listOf(500L, 2_050L, 2_550L), isTotal = true),
        ).inOrder()
    }

    @Test
    fun aPerBookPivot_refusesTheOtherBooksBuckets() {
        // Law 2, enforced rather than trusted: a debit pivot handed a credit
        // bucket would silently put income into a spending total.
        assertThrows(IllegalArgumentException::class.java) {
            XlsxPivots.byCategoryAndMonth(
                name = XlsxPivots.SPENDING_SHEET,
                ledger = LedgerType.DEBIT,
                buckets = listOf(debit(SEP_3, "food", 100), credit(SEP_3, "salary", 100)),
                categoryNames = emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            XlsxPivots.monthlyTotals(debit = listOf(credit(SEP_3, "salary", 100)), credit = emptyList())
        }
    }

    @Test
    fun anEmptyBook_isAnEmptySheet_notARowOfZeroTotals() {
        val sheet = XlsxPivots.byCategoryAndMonth(
            name = XlsxPivots.INCOME_SHEET,
            ledger = LedgerType.CREDIT,
            buckets = emptyList(),
            categoryNames = emptyMap(),
        )

        assertThat(sheet.rows).isEmpty()
        assertThat(XlsxPivots.monthlyTotals(emptyList(), emptyList()).rows).isEmpty()
    }

    private fun debit(day: Int, category: String, minor: Long) = bucket(LedgerType.DEBIT, day, category, minor)
    private fun credit(day: Int, category: String, minor: Long) = bucket(LedgerType.CREDIT, day, category, minor)

    private fun bucket(ledger: LedgerType, day: Int, category: String, minor: Long) = DailyRollupEntity(
        localDate = day,
        ledger = ledger,
        categoryId = category,
        sumMinor = Money(minor),
        txnCount = 1,
    )

    private companion object {
        val SEP_3 = LocalDate.of(2026, 9, 3).toEpochDay().toInt()
        val SEP_20 = LocalDate.of(2026, 9, 20).toEpochDay().toInt()
        val OCT_1 = LocalDate.of(2026, 10, 1).toEpochDay().toInt()
    }
}
