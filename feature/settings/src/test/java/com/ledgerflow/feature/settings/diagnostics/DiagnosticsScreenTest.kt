package com.ledgerflow.feature.settings.diagnostics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.diagnostics.CaptureOutcomes
import com.ledgerflow.core.domain.diagnostics.DuplicateEvidence
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.KeptCopy
import com.ledgerflow.core.domain.diagnostics.PipelineSpeed
import com.ledgerflow.core.domain.diagnostics.ReadQualityReport
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The diagnostics screen, rendered: goldens at font scale 1.0 and 2.0 (§12),
 * BUG17's title rule, and the one navigation the screen offers.
 *
 * Phone width (360 dp), and tall enough that every card is in the capture —
 * a golden of the first half of a scrolling screen reviews the first half. The
 * 2x cases get a taller window of their own; at 2400 dp the first recording
 * stopped halfway down the Speed card.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_1X)
class DiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        state: DiagnosticsUiState,
        fontScale: Float = 1f,
        onOpenSuppressed: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    DiagnosticsScreen(
                        state = state,
                        onEvent = {},
                        onOpenSuppressed = onOpenSuppressed,
                        onBack = {},
                    )
                }
            }
        }
    }

    private fun capture(name: String, state: DiagnosticsUiState, fontScale: Float) {
        show(state, fontScale)
        composeRule.onRoot().captureRoboImage("$GOLDEN_DIR/$name.png", roborazziOptions = LfScreenshotOptions)
    }

    private val full = DiagnosticsUiState(report = DiagnosticsReport.Ready(PreviewDiagnostics))

    /** A fresh install: the first card alone, saying nothing arrived. */
    private val empty = DiagnosticsUiState(
        report = DiagnosticsReport.Ready(
            IngestDiagnostics(
                captured = CaptureOutcomes(0, 0, 0, 0, 0, 0, 0),
                duplicates = DuplicateEvidence(emptyList()),
                reading = ReadQualityReport(0, 0, 0, 0, emptyList()),
                speed = PipelineSpeed(emptyList(), 0, null, null),
                rollup = RollupHealth(null, null),
            ),
        ),
    )

    /**
     * Everything that can go wrong at once: stuck messages and a rollup repair,
     * in `warn`, and four candidates erased so the reading card explains its
     * smaller total.
     */
    private val troubled = DiagnosticsUiState(
        report = DiagnosticsReport.Ready(
            PreviewDiagnostics.copy(
                captured = PreviewDiagnostics.captured.copy(stuck = 3, waiting = 3),
                reading = PreviewDiagnostics.reading.copy(notRecognised = 2),
                rollup = RollupHealth(lastReconciledAt = 1_790_000_000_000L, bucketsRepaired = 4),
                speed = PreviewDiagnostics.speed.copy(oldestWaitingMillis = 3L * 86_400_000L),
            ),
        ),
    )

    @Test fun full_1x() = capture("diagnostics-full-1x", full, 1f)

    @Config(qualifiers = TALL_2X)
    @Test fun full_2x() = capture("diagnostics-full-2x", full, 2f)

    @Test fun empty_1x() = capture("diagnostics-empty-1x", empty, 1f)

    @Config(qualifiers = TALL_2X)
    @Test fun empty_2x() = capture("diagnostics-empty-2x", empty, 2f)

    @Test fun troubled_1x() = capture("diagnostics-troubled-1x", troubled, 1f)

    @Config(qualifiers = TALL_2X)
    @Test fun troubled_2x() = capture("diagnostics-troubled-2x", troubled, 2f)

    // ── BUG17: the title never breaks mid-word ──────────────────────────────
    //
    // The technique of `Bug17_ScreenTitleNeverBreaksMidWordTest`, on the real
    // `TextLayoutResult`. The screen title is the one long single word on this
    // screen, which is exactly BUG17's shape: a word with no break point has to
    // break mid-word once it is wider than the line.

    private fun assertTitleKeepsItsWordsWhole(fontScale: Float) {
        show(full, fontScale)
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(SCREEN_TITLE).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        val layout = results.first()
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (end <= 0 || end >= text.length) continue
            assertWithMessage("\"%s\" breaks mid-word at font scale %s", text, fontScale)
                .that(text[end - 1].isWhitespace() || text[end].isWhitespace()).isTrue()
        }
    }

    @Test fun title_atDefaultFontScale_keepsItsWordsWhole() = assertTitleKeepsItsWordsWhole(1f)

    /** The owner's device setting, where BUG17 was reported. */
    @Test fun title_atTheReportedFontScale_keepsItsWordsWhole() = assertTitleKeepsItsWordsWhole(1.15f)

    @Test fun title_atLargestSupportedFontScale_keepsItsWordsWhole() = assertTitleKeepsItsWordsWhole(2f)

    // ── Behaviour ───────────────────────────────────────────────────────────

    /** "Show in Inbox" is the screen's one way onward, and it goes where it says. */
    @Test
    fun showInInbox_opensTheSuppressedFilter() {
        var opened = 0
        show(full, onOpenSuppressed = { opened++ })

        composeRule.onNodeWithText("Show in Inbox").performScrollTo().performClick()

        assertThat(opened).isEqualTo(1)
    }

    /** No link onto an empty filter: every duplicate's kept copy is gone. */
    @Test
    fun showInInbox_isAbsent_whenNoDuplicateIsStillInTheInbox() {
        show(
            DiagnosticsUiState(
                report = DiagnosticsReport.Ready(
                    PreviewDiagnostics.copy(duplicates = DuplicateEvidence(listOf(KeptCopy(null, 2)))),
                ),
            ),
        )

        composeRule.onNodeWithText("Duplicates caught").performScrollTo().assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithTextCount("Show in Inbox")).isEqualTo(0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}

private const val ROBOLECTRIC_SDK = 34
private const val TALL_1X = "w360dp-h1800dp"
private const val TALL_2X = "w360dp-h3600dp"
private const val GOLDEN_DIR = "src/test/screenshots"
private const val SCREEN_TITLE = "Diagnostics"
