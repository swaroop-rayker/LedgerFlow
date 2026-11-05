package com.ledgerflow.feature.export.importing

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementCheck
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementDateOrder
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.statement.UnsupportedStatement

/**
 * Import a statement into the Inbox (V2-4, owner's option A, 2026-11-05):
 * three pages — the file, which column is which, and a check — then a report.
 */
@Immutable
public data class ImportUiState(
    val step: ImportStep = ImportStep.File,
    val isWorking: Boolean = false,
    /** Why the last file could not be used, until another is chosen. */
    val fileProblem: ImportFileProblem? = null,
    val table: StatementTable? = null,
    val baseCurrency: String = "",
    val columns: StatementColumns? = null,
    /** The file's dates said which order they are in; when not, the Columns page asks. */
    val dateOrderKnown: Boolean = true,
    /** Nothing in the file read as dates and amounts: the Columns page says so. */
    val guessed: Boolean = true,
    /** Which sheet is open on the Columns page. */
    val choosing: ImportChoice? = null,
    val check: StatementCheck? = null,
    val imported: StatementImported? = null,
    /** Adding to the Inbox failed outright. Nothing is lost; the file is unchanged. */
    val addFailed: Boolean = false,
    /** The first rows as the chosen columns read them, for the Columns page. Set with the columns. */
    val preview: List<StatementLine> = emptyList(),
) {
    /** Every column the chosen layout needs exists, and money out is not money in. */
    val columnsComplete: Boolean
        get() {
            val columns = columns ?: return false
            val width = table?.columnCount ?: return false
            val inRange = { column: Int? -> column == null || column in 0 until width }
            val amounts = when (val amounts = columns.amounts) {
                is StatementAmounts.Signed -> inRange(amounts.column)
                is StatementAmounts.Split -> inRange(amounts.moneyOut) && inRange(amounts.moneyIn) &&
                    amounts.moneyOut != amounts.moneyIn
            }
            return inRange(columns.date) && inRange(columns.description) && inRange(columns.currency) && amounts
        }

    public companion object {
        public const val PREVIEW_ROWS: Int = 3
    }
}

public enum class ImportStep { File, Columns, Check, Done }

/** A column the Columns page asks about. */
public enum class ColumnRole { Date, Description, Amount, MoneyOut, MoneyIn, Currency }

/** A sheet the Columns page opens: a column, or one of the three ways of reading the amounts and dates. */
public sealed interface ImportChoice {
    public data class Column(val role: ColumnRole) : ImportChoice
    public data object Layout : ImportChoice
    public data object Negative : ImportChoice
    public data object DateOrder : ImportChoice
}

/** Why a picked file was not used. */
public sealed interface ImportFileProblem {
    public data class Unsupported(val kind: UnsupportedStatement) : ImportFileProblem
    public data object TooLarge : ImportFileProblem
    public data object Unreadable : ImportFileProblem
}

public sealed interface ImportEvent {
    /** The picker returned; null when the user backed out of it. */
    public data class FileChosen(val documentUri: String?) : ImportEvent

    public data class PickerOpened(val choice: ImportChoice) : ImportEvent
    public data object PickerClosed : ImportEvent

    /** A column for [role]; null clears an optional one (description, currency). */
    public data class ColumnChosen(val role: ColumnRole, val column: Int?) : ImportEvent

    /** One signed column, or money out and money in separately. */
    public data class LayoutChosen(val split: Boolean) : ImportEvent
    public data class NegativeChosen(val means: ExtractedDirection) : ImportEvent
    public data class DateOrderChosen(val order: StatementDateOrder) : ImportEvent

    /** Columns → Check. */
    public data object Checked : ImportEvent

    /** Check → added to the Inbox. */
    public data object Added : ImportEvent

    /** Back one page; the screen leaves from the first. */
    public data object Back : ImportEvent
}
