package com.ledgerflow.feature.export.importing

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.statement.CheckStatementUseCase
import com.ledgerflow.core.domain.statement.ImportStatementUseCase
import com.ledgerflow.core.domain.statement.OpenStatementUseCase
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementGuess
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.statement.UnsupportedStatement
import com.ledgerflow.core.testing.statement.FakeStatementImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The three pages (option A): nothing is written until "Add to the Inbox", and back walks the pages. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeStatementImportRepository()

    private val table = StatementTable(
        name = "oct.csv",
        rows = listOf(
            listOf("Date", "Narration", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"),
            listOf("02/10/26", "SWIGGY", "410.00", "", "84,590.00"),
            listOf("03/10/26", "SALARY", "", "85,000.00", "1,69,590.00"),
            listOf("25/10/26", "GROCER", "1,240.50", "", "1,68,349.50"),
        ),
        fingerprint = "fp",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.opened = StatementOpened.Opened(table, StatementGuess.guess(table), baseCurrency = "INR")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ImportViewModel(
        OpenStatementUseCase(repository),
        CheckStatementUseCase(repository),
        ImportStatementUseCase(repository),
    )

    private fun ImportViewModel.chooseFile() = onEvent(ImportEvent.FileChosen("content://oct.csv"))

    @Test
    fun aFile_opensOnTheColumnsPage_withTheGuessAndAPreview() = runTest(dispatcher) {
        val vm = viewModel()

        vm.chooseFile()
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state.step).isEqualTo(ImportStep.Columns)
        assertThat(state.columns?.amounts).isEqualTo(StatementAmounts.Split(moneyOut = 2, moneyIn = 3))
        assertThat(state.preview).hasSize(ImportUiState.PREVIEW_ROWS)
        assertThat((state.preview.first() as StatementLine.Read).description).isEqualTo("SWIGGY")
        assertThat(repository.addCalls).isEmpty()
    }

    @Test
    fun anUnsupportedFile_staysOnTheFilePage_andSaysWhatToDo() = runTest(dispatcher) {
        repository.opened = StatementOpened.Unsupported(UnsupportedStatement.OldExcel)
        val vm = viewModel()

        vm.chooseFile()
        advanceUntilIdle()

        assertThat(vm.state.value.step).isEqualTo(ImportStep.File)
        assertThat(vm.state.value.fileProblem?.message()).contains("save it as .xlsx or CSV")
    }

    @Test
    fun changingAColumn_updatesThePreview_andClosesTheSheet() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFile()
        advanceUntilIdle()
        vm.onEvent(ImportEvent.PickerOpened(ImportChoice.Layout))

        vm.onEvent(ImportEvent.LayoutChosen(split = false))
        vm.onEvent(ImportEvent.NegativeChosen(ExtractedDirection.CREDIT))

        val state = vm.state.value
        assertThat(state.choosing).isNull()
        assertThat(state.columns?.amounts)
            .isEqualTo(StatementAmounts.Signed(column = 2, negativeIs = ExtractedDirection.CREDIT))
    }

    /** Money out and money in in one column is no layout at all. */
    @Test
    fun theSameColumnForOutAndIn_isNotComplete() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFile()
        advanceUntilIdle()

        vm.onEvent(ImportEvent.ColumnChosen(ColumnRole.MoneyIn, 2))
        vm.onEvent(ImportEvent.Checked)
        advanceUntilIdle()

        assertThat(vm.state.value.columnsComplete).isFalse()
        assertThat(vm.state.value.step).isEqualTo(ImportStep.Columns)
        assertThat(repository.checkCalls).isEmpty()
    }

    @Test
    fun next_checks_andAdd_handsOverTheChosenColumns() = runTest(dispatcher) {
        repository.alreadyThere = setOf(2)
        repository.imported = StatementImported(added = 2, suppressed = 1, alreadyImported = 0, failed = 0)
        val vm = viewModel()
        vm.chooseFile()
        advanceUntilIdle()

        vm.onEvent(ImportEvent.Checked)
        advanceUntilIdle()
        val check = requireNotNull(vm.state.value.check)
        assertThat(vm.state.value.step).isEqualTo(ImportStep.Check)
        assertThat(check.toAdd).isEqualTo(2)
        assertThat(check.alreadyThere).isEqualTo(1)
        assertThat(repository.addCalls).isEmpty()

        vm.onEvent(ImportEvent.Added)
        advanceUntilIdle()

        assertThat(repository.addCalls.single().second).isEqualTo(vm.state.value.columns)
        assertThat(vm.state.value.step).isEqualTo(ImportStep.Done)
        assertThat(vm.state.value.imported?.headline()).isEqualTo("2 rows added to the Inbox.")
        assertThat(vm.state.value.imported?.details()).contains("1 row already in LedgerFlow is under Suppressed.")
    }

    @Test
    fun back_walksThePagesInReverse() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFile()
        advanceUntilIdle()
        vm.onEvent(ImportEvent.Checked)
        advanceUntilIdle()

        vm.onEvent(ImportEvent.Back)
        assertThat(vm.state.value.step).isEqualTo(ImportStep.Columns)
        assertThat(vm.state.value.check).isNull()

        vm.onEvent(ImportEvent.Back)
        assertThat(vm.state.value).isEqualTo(ImportUiState())
    }
}
