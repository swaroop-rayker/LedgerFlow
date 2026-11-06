package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.model.Money
import java.time.LocalDate

/**
 * A statement file, read into rows of text (V2-4 Import, `docs/V2-PLAN.md` §3.6).
 *
 * The reader's whole output: what the cells say, nothing interpreted. A CSV
 * and an XLSX arrive here in the same shape, so everything after — guessing
 * the columns, reading amounts and dates, finding what is already in
 * LedgerFlow — is one code path whatever the file was.
 *
 * @param fingerprint a hash of the file's bytes. An imported row's reference
 *   is built from it, so importing the same file again adds nothing.
 */
public data class StatementTable(
    val name: String,
    val rows: List<List<String>>,
    val fingerprint: String,
) {
    public fun cell(row: Int, column: Int?): String =
        column?.let { rows.getOrNull(row)?.getOrNull(it) }?.trim().orEmpty()

    /** The widest row: how many columns there are to choose from. */
    public val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/** Which column is which, as guessed and then confirmed on the Columns page. */
public data class StatementColumns(
    /** The header row; the rows after it are the transactions. -1 when there is none. */
    val headerRow: Int,
    val date: Int,
    val description: Int?,
    val amounts: StatementAmounts,
    /** A column naming each row's currency; null when every row is in the base currency. */
    val currency: Int? = null,
    val dateOrder: StatementDateOrder = StatementDateOrder.DayFirst,
)

/** Where a statement keeps its amounts. */
public sealed interface StatementAmounts {

    /**
     * One column, signed. [negativeIs] says which way a minus sign points;
     * [markColumn], when the statement has one, holds Dr/Cr for each row and
     * outranks the sign.
     */
    public data class Signed(
        val column: Int,
        val negativeIs: ExtractedDirection = ExtractedDirection.DEBIT,
        val markColumn: Int? = null,
    ) : StatementAmounts

    /** Money out and money in, one column each — the usual bank layout. */
    public data class Split(val moneyOut: Int, val moneyIn: Int) : StatementAmounts
}

/** How an all-number date such as 03/04/26 is read. A four-digit first part is always a year. */
public enum class StatementDateOrder { DayFirst, MonthFirst, YearFirst }

/** One transaction row of a statement, as read with the chosen columns. */
public sealed interface StatementLine {

    /** The row's number in the file, counting from 1, so the user can find it. */
    public val line: Int

    public data class Read(
        override val line: Int,
        val date: LocalDate,
        /** Always positive: the direction says which book. */
        val amount: Money,
        val direction: ExtractedDirection,
        val description: String?,
        /** The row's currency code: the base currency unless a currency column says otherwise. */
        val currency: String,
    ) : StatementLine

    public data class Unreadable(
        override val line: Int,
        val reason: UnreadableReason,
    ) : StatementLine
}

/** Why a row that looks like a transaction could not be read. */
public enum class UnreadableReason {
    /** An amount, but no date this reader understands. */
    NoDate,

    /** A date, but no amount in the amount columns. */
    NoAmount,

    /** Money out and money in on the same row. */
    BothAmounts,

    /** An amount of nothing. */
    ZeroAmount,
}
