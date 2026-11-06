package com.ledgerflow.feature.export.importing

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.statement.CheckedRow
import com.ledgerflow.core.domain.statement.StatementCheck
import com.ledgerflow.core.domain.statement.StatementGuess
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementReading
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.statement.UnsupportedStatement
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Import's three pages and its report at font scale 1.0 and 2.0 (§12), with
 * §9.6's checks; the check page in RTL as well, where each line's description
 * and amount trade sides. Rows are made up; the layout is a real bank's.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class ImportScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        state: ImportUiState,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        LfTheme { ImportScreen(state = state, onEvent = {}, onLeave = {}, onOpenInbox = {}) }
    }

    private val table = StatementTable(
        name = "statement_oct.csv",
        rows = listOf(
            listOf("Example Bank Ltd"),
            listOf("Date", "Narration", "Chq./Ref.No.", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"),
            listOf("02/10/26", "UPI-SWIGGY-BANGALORE", "0001", "410.00", "", "84,590.00"),
            listOf("03/10/26", "NEFT CR-SALARY ACME LTD", "0002", "", "85,000.00", "1,69,590.00"),
            listOf("25/10/26", "POS CORNER GROCER", "0003", "1,240.50", "", "1,68,349.50"),
            listOf("26/10/26", "UPI-CAFE COFFEE DAY", "0004", "180.00", "", "1,68,169.50"),
            listOf("28/10/26", "", "", "", "", ""),
        ),
        fingerprint = "0123456789abcdef",
    )
    private val columns = requireNotNull(StatementGuess.guess(table)).columns

    private val file = ImportUiState(fileProblem = ImportFileProblem.Unsupported(UnsupportedStatement.OldExcel))

    private val columnsPage = ImportUiState(
        step = ImportStep.Columns,
        table = table,
        baseCurrency = "INR",
        columns = columns,
        preview = StatementReading.read(table, columns, "INR", limit = ImportUiState.PREVIEW_ROWS),
    )

    private val checkPage = run {
        val lines = StatementReading.read(table, columns, "INR")
        columnsPage.copy(
            step = ImportStep.Check,
            check = StatementCheck(
                baseCurrency = "INR",
                rows = lines.filterIsInstance<StatementLine.Read>().map { CheckedRow(it, alreadyThere = it.line == 3) },
                unreadable = lines.filterIsInstance<StatementLine.Unreadable>(),
            ),
        )
    }

    private val done = checkPage.copy(
        step = ImportStep.Done,
        imported = StatementImported(added = 3, suppressed = 1, alreadyImported = 0, failed = 0),
    )

    @Test fun file_1x() = capture("import-file-1x", file, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun file_2x() = capture("import-file-2x", file, 2f)

    @Test fun columns_1x() = capture("import-columns-1x", columnsPage, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun columns_2x() = capture("import-columns-2x", columnsPage, 2f)

    @Test fun check_1x() = capture("import-check-1x", checkPage, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun check_2x() = capture("import-check-2x", checkPage, 2f)

    @Test fun check_1x_rtl() = capture("import-check-1x-rtl", checkPage, 1f, LayoutDirection.Rtl)

    @Test fun done_1x() = capture("import-done-1x", done, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun done_2x() = capture("import-done-2x", done, 2f)
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportScreenGoldensDarkTest : ImportScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
