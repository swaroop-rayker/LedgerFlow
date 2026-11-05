package com.ledgerflow.feature.export.importing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNavRow
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfOptionsSheet
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSheetOption
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.statement.CheckedRow
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementCheck
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementDateOrder
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.statement.UnreadableReason
import com.ledgerflow.core.domain.statement.UnsupportedStatement
import com.ledgerflow.core.model.LedgerType

/**
 * Import a statement into the Inbox (V2-4, D8; owner's option A, 2026-11-05).
 *
 * @param onLeave back out of the screen, from the first page or the report.
 * @param onOpenInbox the report's way on: the rows are waiting there.
 */
@Composable
public fun ImportRoute(onLeave: () -> Unit, onOpenInbox: () -> Unit) {
    val viewModel: ImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImportScreen(state = state, onEvent = viewModel::onEvent, onLeave = onLeave, onOpenInbox = onOpenInbox)
}

/** Stateless: state in, one event lambda out (CLAUDE.md §5). */
@Composable
public fun ImportScreen(
    state: ImportUiState,
    onEvent: (ImportEvent) -> Unit,
    onLeave: () -> Unit,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val back: () -> Unit = when (state.step) {
        ImportStep.File, ImportStep.Done -> onLeave
        ImportStep.Columns, ImportStep.Check -> { { onEvent(ImportEvent.Back) } }
    }
    BackHandler(enabled = !state.isWorking, onBack = back)
    when (state.step) {
        ImportStep.File -> FilePage(state, onEvent, back, modifier)
        ImportStep.Columns -> ColumnsPage(state, onEvent, back, modifier)
        ImportStep.Check -> CheckPage(state, onEvent, back, modifier)
        ImportStep.Done -> DonePage(state, onOpenInbox, back, modifier)
    }
}

// ── 1. The file ─────────────────────────────────────────────────────────────

@Composable
private fun FilePage(state: ImportUiState, onEvent: (ImportEvent) -> Unit, back: () -> Unit, modifier: Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onEvent(ImportEvent.FileChosen(uri?.toString()))
    }
    LfFormPage(
        title = "Import a statement",
        subtitle = "Step 1 of 3",
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Choose a file",
                    // A CSV's type varies by phone and by app; the picker cannot filter for it.
                    onClick = { picker.launch(arrayOf("*/*")) },
                    loading = state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        modifier = modifier,
        onBack = back,
        backEnabled = !state.isWorking,
    ) {
        LfCard {
            Text(
                text = "A CSV or Excel (.xlsx) file from your bank, or any spreadsheet with dates and " +
                    "amounts. Each row goes to the Inbox for you to approve: nothing reaches your ledger " +
                    "until you do.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
        state.fileProblem?.let { ProblemLine(it.message()) }
    }
}

internal fun ImportFileProblem.message(): String = when (this) {
    is ImportFileProblem.Unsupported -> when (kind) {
        UnsupportedStatement.OldExcel ->
            "This is an older Excel file (.xls). Open it in a spreadsheet app, save it as .xlsx or CSV, " +
                "and choose that."
        UnsupportedStatement.Pdf ->
            "This is a PDF. LedgerFlow reads CSV and Excel (.xlsx) statements; most banks offer one " +
                "beside the PDF."
    }
    ImportFileProblem.TooLarge -> "This file is too large to import. Export a shorter period and try again."
    ImportFileProblem.Unreadable -> "That file couldn't be read. Choose it again, or try another."
}

// ── 2. Which column is which ────────────────────────────────────────────────

@Composable
private fun ColumnsPage(state: ImportUiState, onEvent: (ImportEvent) -> Unit, back: () -> Unit, modifier: Modifier) {
    val table = state.table ?: return
    val columns = state.columns ?: return
    state.choosing?.let { ChoiceSheet(it, table, columns, onEvent) }
    LfFormPage(
        title = "Which column is which?",
        subtitle = "Step 2 of 3 · ${table.name}",
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Next",
                    onClick = { onEvent(ImportEvent.Checked) },
                    enabled = state.columnsComplete,
                    loading = state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        modifier = modifier,
        onBack = back,
        backEnabled = !state.isWorking,
    ) {
        if (!state.guessed) {
            Text(
                text = "LedgerFlow couldn't tell which column is which. Choose them below.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
        ColumnRows(state, table, columns, onEvent)
        PreviewCard(state.preview)
    }
}

@Composable
private fun ColumnRows(
    state: ImportUiState,
    table: StatementTable,
    columns: StatementColumns,
    onEvent: (ImportEvent) -> Unit,
) {
    fun open(choice: ImportChoice): () -> Unit = { onEvent(ImportEvent.PickerOpened(choice)) }
    fun label(column: Int?): String = column?.let { columnLabel(table, columns.headerRow, it) } ?: "None"

    LfPanel {
        LfNavRow(title = "Date", value = label(columns.date), onClick = open(ImportChoice.Column(ColumnRole.Date)))
        LfDivider()
        LfNavRow(
            title = "Description",
            value = label(columns.description),
            onClick = open(ImportChoice.Column(ColumnRole.Description)),
        )
        LfDivider()
        LfNavRow(title = "Amounts", value = layoutLabel(columns.amounts), onClick = open(ImportChoice.Layout))
        when (val amounts = columns.amounts) {
            is StatementAmounts.Signed -> {
                LfDivider()
                LfNavRow(
                    title = "Amount",
                    value = label(amounts.column),
                    onClick = open(ImportChoice.Column(ColumnRole.Amount)),
                )
                LfDivider()
                LfNavRow(
                    title = "A minus means",
                    value = negativeLabel(amounts.negativeIs),
                    onClick = open(ImportChoice.Negative),
                )
            }
            is StatementAmounts.Split -> {
                LfDivider()
                LfNavRow(
                    title = "Money out",
                    value = label(amounts.moneyOut),
                    onClick = open(ImportChoice.Column(ColumnRole.MoneyOut)),
                )
                LfDivider()
                LfNavRow(
                    title = "Money in",
                    value = label(amounts.moneyIn),
                    onClick = open(ImportChoice.Column(ColumnRole.MoneyIn)),
                )
            }
        }
        LfDivider()
        LfNavRow(
            title = "Currency",
            value = columns.currency?.let(::label) ?: "All ${state.baseCurrency}",
            onClick = open(ImportChoice.Column(ColumnRole.Currency)),
        )
        if (!state.dateOrderKnown) {
            LfDivider()
            LfNavRow(
                title = "Dates read as",
                value = orderLabel(columns.dateOrder),
                onClick = open(ImportChoice.DateOrder),
            )
        }
    }
}

@Composable
private fun ChoiceSheet(
    choice: ImportChoice,
    table: StatementTable,
    columns: StatementColumns,
    onEvent: (ImportEvent) -> Unit,
) {
    val close = { onEvent(ImportEvent.PickerClosed) }
    val options: List<LfSheetOption> = when (choice) {
        is ImportChoice.Column -> buildList {
            if (choice.role == ColumnRole.Description || choice.role == ColumnRole.Currency) {
                add(LfSheetOption(label = "None", onClick = { onEvent(ImportEvent.ColumnChosen(choice.role, null)) }))
            }
            for (column in 0 until table.columnCount) {
                add(
                    LfSheetOption(
                        label = columnLabel(table, columns.headerRow, column),
                        value = sampleOf(table, columns.headerRow, column),
                        onClick = { onEvent(ImportEvent.ColumnChosen(choice.role, column)) },
                    ),
                )
            }
        }
        ImportChoice.Layout -> listOf(
            LfSheetOption("One column", onClick = { onEvent(ImportEvent.LayoutChosen(split = false)) }),
            LfSheetOption("Money out and in", onClick = { onEvent(ImportEvent.LayoutChosen(split = true)) }),
        )
        ImportChoice.Negative -> listOf(
            LfSheetOption("Money out", onClick = { onEvent(ImportEvent.NegativeChosen(ExtractedDirection.DEBIT)) }),
            LfSheetOption("Money in", onClick = { onEvent(ImportEvent.NegativeChosen(ExtractedDirection.CREDIT)) }),
        )
        ImportChoice.DateOrder -> listOf(StatementDateOrder.DayFirst, StatementDateOrder.MonthFirst).map { order ->
            LfSheetOption(orderLabel(order), onClick = { onEvent(ImportEvent.DateOrderChosen(order)) })
        }
    }
    LfOptionsSheet(options = options, onDismiss = close, title = choice.title())
}

/** The first rows as read, so a wrong column shows before the next page. */
@Composable
private fun PreviewCard(preview: List<StatementLine>) {
    if (preview.isEmpty()) return
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(text = "First rows, as read", style = LfTheme.type.label, color = LfTheme.colors.text.primary)
            preview.forEach { line ->
                when (line) {
                    is StatementLine.Read -> ReadLine(line)
                    is StatementLine.Unreadable -> Text(
                        text = unreadableText(line),
                        style = LfTheme.type.bodySmall,
                        color = LfTheme.colors.status.danger,
                    )
                }
            }
        }
    }
}

// ── 3. Check and add ────────────────────────────────────────────────────────

@Composable
private fun CheckPage(state: ImportUiState, onEvent: (ImportEvent) -> Unit, back: () -> Unit, modifier: Modifier) {
    val check = state.check ?: return
    val table = state.table ?: return
    LfFormPage(
        title = "Check and add",
        subtitle = "Step 3 of 3 · ${table.name}",
        actionBar = {
            LfActionBar {
                LfButton(
                    text = addLabel(check.toAdd),
                    onClick = { onEvent(ImportEvent.Added) },
                    enabled = check.toAdd > 0,
                    loading = state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        modifier = modifier,
        onBack = back,
        backEnabled = !state.isWorking,
    ) {
        BookCounts(check)
        if (check.alreadyThere > 0 || check.unreadable.isNotEmpty()) SetAsideCard(check)
        NewRowsCard(check.rows)
        if (state.addFailed) ProblemLine("Nothing was added: the Inbox couldn't be written. Try again.")
    }
}

/** Each book counted and totalled on its own (Law 2); other currencies counted, never totalled in. */
@Composable
private fun BookCounts(check: StatementCheck) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(text = "New in this file", style = LfTheme.type.label, color = LfTheme.colors.text.primary)
            val base = check.baseCurrency
            val spent = MoneyFormat.directional(check.expensesTotal.minor, base, LedgerType.DEBIT)
            val received = MoneyFormat.directional(check.incomeTotal.minor, base, LedgerType.CREDIT)
            CountLine("Expenses", check.expenses, spent)
            CountLine("Income", check.income, received)
            if (check.otherCurrency > 0) {
                Text(
                    text = "${plural(check.otherCurrency, "row")} in other currencies: not in these totals. " +
                        "You give the ${check.baseCurrency} amount when you approve.",
                    style = LfTheme.type.bodySmall,
                    color = LfTheme.colors.text.secondary,
                )
            }
        }
    }
}

@Composable
private fun SetAsideCard(check: StatementCheck) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            if (check.alreadyThere > 0) {
                CountLine("Already in LedgerFlow", check.alreadyThere, null)
                Text(
                    text = "Kept under Suppressed in the Inbox, not added again.",
                    style = LfTheme.type.bodySmall,
                    color = LfTheme.colors.text.secondary,
                )
            }
            if (check.unreadable.isNotEmpty()) {
                CountLine("Couldn't read", check.unreadable.size, null)
                check.unreadable.take(UNREADABLE_SHOWN).forEach { line ->
                    ProblemLine(unreadableText(line))
                }
                if (check.unreadable.size > UNREADABLE_SHOWN) {
                    Text(
                        text = "and ${check.unreadable.size - UNREADABLE_SHOWN} more. These stay in your file.",
                        style = LfTheme.type.bodySmall,
                        color = LfTheme.colors.text.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewRowsCard(rows: List<CheckedRow>) {
    val fresh = rows.filterNot { it.alreadyThere }
    if (fresh.isEmpty()) return
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            fresh.take(ROWS_SHOWN).forEach { row ->
                ReadLine(row.line)
            }
            if (fresh.size > ROWS_SHOWN) {
                Text(
                    text = "and ${fresh.size - ROWS_SHOWN} more",
                    style = LfTheme.type.bodySmall,
                    color = LfTheme.colors.text.secondary,
                )
            }
        }
    }
}

// ── The report ──────────────────────────────────────────────────────────────

@Composable
private fun DonePage(state: ImportUiState, onOpenInbox: () -> Unit, back: () -> Unit, modifier: Modifier) {
    val imported = state.imported ?: return
    LfFormPage(
        title = "Added to the Inbox",
        actionBar = {
            LfActionBar {
                LfButton(text = "Open the Inbox", onClick = onOpenInbox, modifier = Modifier.fillMaxWidth())
            }
        },
        modifier = modifier,
        onBack = back,
    ) {
        LfNotice(tone = LfNoticeTone.Success, title = imported.headline(), body = imported.details())
    }
}

internal fun StatementImported.headline(): String = when (added) {
    0 -> "Nothing new was added."
    1 -> "1 row added to the Inbox."
    else -> "$added rows added to the Inbox."
}

internal fun StatementImported.details(): String = buildString {
    append("Nothing is in your ledger until you approve it.")
    if (suppressed > 0) {
        val verb = if (suppressed == 1) "is" else "are"
        append(" ${plural(suppressed, "row")} already in LedgerFlow $verb under Suppressed.")
    }
    if (alreadyImported > 0) append(" ${plural(alreadyImported, "row")} came from an earlier import of this file.")
    if (failed > 0) append(" ${plural(failed, "row")} couldn't be added and stay in your file.")
}

// ── Pieces ──────────────────────────────────────────────────────────────────

/** One row: the date and description at the start, the amount at the end, in its book's colour. */
@Composable
private fun ReadLine(line: StatementLine.Read) {
    val date = line.date
    val description = line.description
    val ledger = line.direction.toLedgerOrNull() ?: LedgerType.DEBIT
    LfAdaptiveRow(
        leading = {
            Text(
                text = DisplayDates.dayMonth(date) + (description?.let { " · $it" } ?: ""),
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.primary,
                // A bank's narration can run to a paragraph; two lines say enough.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Text(
                text = MoneyFormat.directional(line.amount.minor, line.currency, ledger),
                style = LfTheme.type.figureLabel,
                color = when (ledger) {
                    LedgerType.DEBIT -> LfTheme.colors.money.outgoing
                    LedgerType.CREDIT -> LfTheme.colors.money.incoming
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CountLine(label: String, count: Int, total: String?) {
    LfAdaptiveRow(
        leading = { Text(text = label, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary) },
        trailing = {
            Text(
                text = if (total == null) count.toString() else "$count · $total",
                style = LfTheme.type.figureLabel,
                color = LfTheme.colors.text.primary,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProblemLine(text: String) {
    Text(text = text, style = LfTheme.type.bodySmall, color = LfTheme.colors.status.danger)
}

/** A column by its heading, or by its letter when the file has none. */
internal fun columnLabel(table: StatementTable, headerRow: Int, column: Int): String {
    val heading = if (headerRow >= 0) table.cell(headerRow, column) else ""
    return heading.ifBlank { "Column ${letterOf(column)}" }
}

/** The first value under a column, to tell two similar headings apart in the picker. */
private fun sampleOf(table: StatementTable, headerRow: Int, column: Int): String? =
    (headerRow + 1 until table.rows.size).asSequence()
        .map { table.cell(it, column) }
        .firstOrNull { it.isNotBlank() }
        ?.let { if (it.length > SAMPLE_LENGTH) it.take(SAMPLE_LENGTH) + "…" else it }

private fun letterOf(column: Int): String {
    var n = column + 1
    val letters = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % ALPHABET
        letters.insert(0, 'A' + rem)
        n = (n - 1) / ALPHABET
    }
    return letters.toString()
}

private fun layoutLabel(amounts: StatementAmounts): String = when (amounts) {
    is StatementAmounts.Signed -> "One column"
    is StatementAmounts.Split -> "Money out and in"
}

private fun negativeLabel(direction: ExtractedDirection): String =
    if (direction == ExtractedDirection.CREDIT) "Money in" else "Money out"

private fun orderLabel(order: StatementDateOrder): String = when (order) {
    StatementDateOrder.DayFirst -> "Day first (31/12)"
    StatementDateOrder.MonthFirst -> "Month first (12/31)"
    StatementDateOrder.YearFirst -> "Year first"
}

private fun ImportChoice.title(): String = when (this) {
    is ImportChoice.Column -> when (role) {
        ColumnRole.Date -> "Date"
        ColumnRole.Description -> "Description"
        ColumnRole.Amount -> "Amount"
        ColumnRole.MoneyOut -> "Money out"
        ColumnRole.MoneyIn -> "Money in"
        ColumnRole.Currency -> "Currency"
    }
    ImportChoice.Layout -> "Amounts"
    ImportChoice.Negative -> "A minus means"
    ImportChoice.DateOrder -> "Dates read as"
}

internal fun unreadableText(line: StatementLine.Unreadable): String = "Line ${line.line}: " + when (line.reason) {
    UnreadableReason.NoDate -> "no date LedgerFlow can read"
    UnreadableReason.NoAmount -> "no amount"
    UnreadableReason.BothAmounts -> "money out and money in both filled"
    UnreadableReason.ZeroAmount -> "an amount of zero"
}

private fun addLabel(count: Int): String = when (count) {
    0 -> "Nothing new to add"
    1 -> "Add 1 to the Inbox"
    else -> "Add $count to the Inbox"
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

private const val UNREADABLE_SHOWN = 3
private const val ROWS_SHOWN = 8
private const val SAMPLE_LENGTH = 24
private const val ALPHABET = 26

// ── Previews (CLAUDE.md §5) ─────────────────────────────────────────────────

private val previewTable = StatementTable(
    name = "statement_oct.csv",
    rows = listOf(
        listOf("Date", "Narration", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"),
        listOf("02/10/26", "UPI-SWIGGY", "410.00", "", "84,590.00"),
        listOf("03/10/26", "NEFT CR-SALARY ACME", "", "85,000.00", "1,69,590.00"),
        listOf("25/10/26", "POS CORNER GROCER", "1,240.50", "", "1,68,349.50"),
    ),
    fingerprint = "0123456789abcdef",
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ImportColumnsPreview() {
    LfTheme {
        ImportScreen(
            state = ImportUiState(
                step = ImportStep.Columns,
                table = previewTable,
                baseCurrency = "INR",
                columns = StatementColumns(0, 0, 1, StatementAmounts.Split(2, 3)),
            ),
            onEvent = {},
            onLeave = {},
            onOpenInbox = {},
        )
    }
}
