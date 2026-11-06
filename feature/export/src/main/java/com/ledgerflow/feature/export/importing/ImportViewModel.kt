package com.ledgerflow.feature.export.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.statement.CheckStatementUseCase
import com.ledgerflow.core.domain.statement.ImportStatementUseCase
import com.ledgerflow.core.domain.statement.OpenStatementUseCase
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementReading
import com.ledgerflow.core.domain.statement.StatementTable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Import a statement (V2-4): file → columns → check → added (option A).
 *
 * Nothing is written until "Add to the Inbox"; every page before it only
 * reads. Back walks the pages in reverse, and leaving the file page leaves
 * the screen.
 */
@HiltViewModel
public class ImportViewModel @Inject constructor(
    private val openStatement: OpenStatementUseCase,
    private val checkStatement: CheckStatementUseCase,
    private val importStatement: ImportStatementUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    public val state: StateFlow<ImportUiState> = _state.asStateFlow()

    public fun onEvent(event: ImportEvent) {
        if (_state.value.isWorking) return
        when (event) {
            is ImportEvent.FileChosen -> event.documentUri?.let(::open)
            is ImportEvent.PickerOpened -> _state.update { it.copy(choosing = event.choice) }
            ImportEvent.PickerClosed -> _state.update { it.copy(choosing = null) }
            is ImportEvent.ColumnChosen -> changeColumns { choose(it, event.role, event.column) }
            is ImportEvent.LayoutChosen -> changeColumns { layout(it, event.split) }
            is ImportEvent.NegativeChosen -> changeColumns { negative(it, event.means) }
            is ImportEvent.DateOrderChosen -> changeColumns { it.copy(dateOrder = event.order) }
            ImportEvent.Checked -> check()
            ImportEvent.Added -> add()
            ImportEvent.Back -> back()
        }
    }

    private fun open(documentUri: String) {
        _state.update { it.copy(isWorking = true, fileProblem = null) }
        viewModelScope.launch {
            when (val opened = openStatement(documentUri)) {
                is StatementOpened.Opened -> {
                    val guess = opened.guess
                    val columns = guess?.columns ?: fallbackColumns(opened.table)
                    _state.value = ImportUiState(
                        step = ImportStep.Columns,
                        table = opened.table,
                        baseCurrency = opened.baseCurrency,
                        columns = columns,
                        dateOrderKnown = guess?.dateOrderKnown ?: false,
                        guessed = guess != null,
                        preview = previewOf(opened.table, columns, opened.baseCurrency),
                    )
                }
                is StatementOpened.Unsupported -> problem(ImportFileProblem.Unsupported(opened.kind))
                StatementOpened.TooLarge -> problem(ImportFileProblem.TooLarge)
                StatementOpened.Unreadable -> problem(ImportFileProblem.Unreadable)
            }
        }
    }

    private fun problem(problem: ImportFileProblem) {
        _state.update { it.copy(isWorking = false, fileProblem = problem) }
    }

    /** Nothing read as dates and amounts: start from the first columns, for the user to correct. */
    private fun fallbackColumns(table: StatementTable): StatementColumns {
        val width = table.columnCount
        return StatementColumns(
            headerRow = 0,
            date = 0,
            description = 1.takeIf { it < width },
            amounts = StatementAmounts.Signed(column = minOf(2, width - 1).coerceAtLeast(0)),
        )
    }

    private fun changeColumns(change: (StatementColumns) -> StatementColumns) {
        _state.update { current ->
            val columns = current.columns ?: return@update current
            val table = current.table ?: return@update current
            val changed = change(columns)
            current.copy(
                columns = changed,
                choosing = null,
                check = null,
                preview = previewOf(table, changed, current.baseCurrency),
            )
        }
    }

    /** An optional column may be cleared; a required one only changed. */
    private fun choose(columns: StatementColumns, role: ColumnRole, column: Int?): StatementColumns = when (role) {
        ColumnRole.Description -> columns.copy(description = column)
        ColumnRole.Currency -> columns.copy(currency = column)
        ColumnRole.Date, ColumnRole.Amount, ColumnRole.MoneyOut, ColumnRole.MoneyIn ->
            column?.let { required(columns, role, it) } ?: columns
    }

    private fun required(columns: StatementColumns, role: ColumnRole, column: Int): StatementColumns = when (role) {
        ColumnRole.Date -> columns.copy(date = column)
        ColumnRole.Amount -> {
            val signed = columns.amounts as? StatementAmounts.Signed
            columns.copy(amounts = signed?.copy(column = column) ?: StatementAmounts.Signed(column))
        }
        ColumnRole.MoneyOut -> columns.copy(amounts = splitOf(columns).copy(moneyOut = column))
        ColumnRole.MoneyIn -> columns.copy(amounts = splitOf(columns).copy(moneyIn = column))
        ColumnRole.Description, ColumnRole.Currency -> columns
    }

    private fun layout(columns: StatementColumns, split: Boolean): StatementColumns = when {
        split && columns.amounts is StatementAmounts.Signed -> columns.copy(amounts = splitOf(columns))
        !split && columns.amounts is StatementAmounts.Split ->
            columns.copy(amounts = StatementAmounts.Signed((columns.amounts as StatementAmounts.Split).moneyOut))
        else -> columns
    }

    /** The split layout, starting from wherever the single amount column was. */
    private fun splitOf(columns: StatementColumns): StatementAmounts.Split = when (val amounts = columns.amounts) {
        is StatementAmounts.Split -> amounts
        is StatementAmounts.Signed -> StatementAmounts.Split(moneyOut = amounts.column, moneyIn = amounts.column + 1)
    }

    private fun negative(columns: StatementColumns, means: ExtractedDirection): StatementColumns {
        val signed = columns.amounts as? StatementAmounts.Signed ?: return columns
        return columns.copy(amounts = signed.copy(negativeIs = means))
    }

    private fun check() {
        val current = _state.value
        val table = current.table ?: return
        val columns = current.columns ?: return
        if (!current.columnsComplete) return
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val check = checkStatement(table, columns)
            _state.update { it.copy(isWorking = false, check = check, step = ImportStep.Check) }
        }
    }

    private fun add() {
        val current = _state.value
        val table = current.table ?: return
        val columns = current.columns ?: return
        if (current.step != ImportStep.Check) return
        _state.update { it.copy(isWorking = true, addFailed = false) }
        viewModelScope.launch {
            val imported = runCatching { importStatement(table, columns) }.getOrNull()
            _state.update {
                if (imported == null) {
                    it.copy(isWorking = false, addFailed = true)
                } else {
                    it.copy(isWorking = false, imported = imported, step = ImportStep.Done)
                }
            }
        }
    }

    private fun back() {
        _state.update { current ->
            when (current.step) {
                ImportStep.Check -> current.copy(step = ImportStep.Columns, check = null, addFailed = false)
                ImportStep.Columns -> ImportUiState()
                ImportStep.File, ImportStep.Done -> current
            }
        }
    }

    private fun previewOf(table: StatementTable, columns: StatementColumns, base: String) =
        StatementReading.read(table, columns, base, limit = ImportUiState.PREVIEW_ROWS)
}
