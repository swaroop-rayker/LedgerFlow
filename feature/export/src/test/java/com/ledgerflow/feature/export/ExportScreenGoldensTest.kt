package com.ledgerflow.feature.export

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.export.ExportFormat
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
 * Export at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks:
 * ready (Excel, the default since ADR-0004) and done, whose receipt names the
 * counts. BUG17's header shape lives here; `ExportTitleNeverBreaksMidWordTest`
 * guards the title, these guard the rest of the page.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class ExportScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: ExportUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme {
                ExportScreen(
                    state = state,
                    suggestedFileName = "LedgerFlow-export-2026-08-21.xlsx",
                    onEvent = {},
                    onBack = {},
                )
            }
        }

    private val done = ExportUiState(
        status = ExportStatus.Done(fileCount = 24, rowCount = 1_482, format = ExportFormat.XLSX),
    )

    @Test fun ready_1x() = capture("export-ready-1x", ExportUiState(), 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun ready_2x() = capture("export-ready-2x", ExportUiState(), 2f)

    @Test fun done_1x() = capture("export-done-1x", done, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun done_2x() = capture("export-done-2x", done, 2f)
}
