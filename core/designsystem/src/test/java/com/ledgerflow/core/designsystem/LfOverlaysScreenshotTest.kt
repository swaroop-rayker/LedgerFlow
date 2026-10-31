package com.ledgerflow.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfBottomSheet
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDatePickerDialog
import com.ledgerflow.core.designsystem.component.LfDateRangePickerDialog
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.pattern.LfConfirmSheet
import com.ledgerflow.core.designsystem.pattern.LfFilterChoice
import com.ledgerflow.core.designsystem.pattern.LfFilterGroup
import com.ledgerflow.core.designsystem.pattern.LfFilterSheet
import com.ledgerflow.core.designsystem.pattern.LfOptionsSheet
import com.ledgerflow.core.designsystem.pattern.LfSheetOption
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The themed wrappers for Material's pickers, sheet and snackbar (DESIGN_SYSTEM_PLAN
 * §6.4, step C2), each drawn as a screen shows it.
 *
 * The whole screen is captured, not the test's root node, because a dialog and
 * a modal sheet are windows of their own: `captureRoboImage` on the root would
 * show only the page behind them. Every wrapper reproduces what its screens drew
 * before C2, so these are the reference a later visual step changes on purpose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [OVERLAYS_ROBOLECTRIC_SDK], qualifiers = OVERLAYS_WINDOW)
@OptIn(ExperimentalRoborazziApi::class, ExperimentalMaterial3Api::class)
open class LfOverlaysScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * [fontScale] is set on the device configuration, not as a `LocalDensity`
     * override: a sheet or dialog is a window of its own and takes its density
     * from the configuration, so an override draws the page at 2.0 and the
     * sheet at 1.0.
     *
     * **No right-to-left sheet golden.** Under Robolectric a dialog window stays
     * left-to-right whatever the configuration says (tried: `ldrtl`, an `ar`
     * locale, a `LocalLayoutDirection` override), so a golden named RTL would
     * show LTR. The parts inside a sheet -- `LfNavRow`, `LfChip` -- have real RTL
     * goldens on the pages that use them, and sheets in RTL are a device check.
     */
    private fun capture(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        composeRule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    Box(Modifier.fillMaxSize().background(LfTheme.colors.surface.canvas)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    @Test
    fun datePickerDialog() = capture("lf-date-picker-dialog") {
        LfDatePickerDialog(
            state = rememberDatePickerState(initialSelectedDateMillis = SELECTED_DAY_MILLIS),
            onDismiss = {},
            confirmButton = { LfButton(text = "Set", onClick = {}, style = LfButtonStyle.Inline) },
            dismissButton = { LfButton(text = "Cancel", onClick = {}, style = LfButtonStyle.Inline) },
        )
    }

    @Test
    fun dateRangePickerDialog() = capture("lf-date-range-picker-dialog") {
        LfDateRangePickerDialog(
            state = rememberDateRangePickerState(
                initialSelectedStartDateMillis = RANGE_START_MILLIS,
                initialSelectedEndDateMillis = SELECTED_DAY_MILLIS,
            ),
            onDismiss = {},
            confirmButton = { LfButton(text = "Apply", onClick = {}, style = LfButtonStyle.Inline) },
            dismissButton = { LfButton(text = "Cancel", onClick = {}, style = LfButtonStyle.Inline) },
            headline = {
                Text(
                    text = "10 – 19 Aug 2026",
                    style = LfTheme.type.heading,
                    modifier = Modifier.padding(start = LfTheme.space.screenEdge, bottom = LfTheme.space.itemGap),
                )
            },
        )
    }

    /** The Add sheet's shape and content. */
    @Test
    fun bottomSheet() = capture("lf-bottom-sheet") {
        LfBottomSheet(onDismiss = {}) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LfTheme.space.cardInset),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
            ) {
                LfButton(
                    text = "Scan a receipt",
                    onClick = {},
                    style = LfButtonStyle.Outlined,
                    modifier = Modifier.fillMaxWidth(),
                )
                LfButton(text = "Manual entry", onClick = {}, modifier = Modifier.fillMaxWidth())
                LfButton(
                    text = "Inbox (3)",
                    onClick = {},
                    style = LfButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private val addOptions = listOf(
        LfSheetOption(label = "Scan a receipt", onClick = {}),
        LfSheetOption(label = "Manual entry", onClick = {}),
        LfSheetOption(label = "Inbox", onClick = {}, value = "3"),
    )

    /** The Add sheet since step E1: the options variant. */
    @Test
    fun optionsSheet() = capture("lf-options-sheet") { LfOptionsSheet(options = addOptions, onDismiss = {}) }

    @Test
    fun optionsSheet_2x() = capture("lf-options-sheet-2x", fontScale = 2f) {
        LfOptionsSheet(options = addOptions, onDismiss = {})
    }

    @Test
    fun confirmSheet() = capture("lf-confirm-sheet") {
        LfConfirmSheet(
            title = "Sign out of Google Drive?",
            body = "Backups stop going to Drive. Everything on this phone stays as it is.",
            confirmLabel = "Sign out",
            onConfirm = {},
            onDismiss = {},
        )
    }

    private val filterGroups = listOf(
        LfFilterGroup(
            label = "Category",
            choices = listOf(
                LfFilterChoice(label = "Groceries", selected = true, onToggle = {}),
                LfFilterChoice(label = "Eating out", selected = false, onToggle = {}),
                LfFilterChoice(label = "Transport", selected = true, onToggle = {}),
                LfFilterChoice(label = "Rent", selected = false, onToggle = {}),
            ),
        ),
        LfFilterGroup(
            label = "Source",
            choices = listOf(
                LfFilterChoice(label = "SMS", selected = false, onToggle = {}),
                LfFilterChoice(label = "Notification", selected = false, onToggle = {}),
                LfFilterChoice(label = "Receipt", selected = true, onToggle = {}),
                LfFilterChoice(label = "By hand", selected = false, onToggle = {}),
            ),
        ),
    )

    @Composable
    private fun Filters() = LfFilterSheet(
        title = "Filters",
        groups = filterGroups,
        onReset = {},
        onApply = {},
        onDismiss = {},
    )

    @Test
    fun filterSheet() = capture("lf-filter-sheet") { Filters() }

    @Test
    fun filterSheet_2x() = capture("lf-filter-sheet-2x", fontScale = 2f) { Filters() }


    /** A message with its action, through `LfScaffold`'s host as every screen gets it. */
    @Test
    fun snackbar() = capture("lf-snackbar") {
        val host = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            host.showSnackbar(
                message = "Moved to Deleted entries",
                actionLabel = "Undo",
                duration = SnackbarDuration.Indefinite,
            )
        }
        LfScaffold(snackbarHostState = host) {}
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfOverlaysScreenshotDarkTest : LfOverlaysScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}

private const val OVERLAYS_ROBOLECTRIC_SDK = 34

/** Wide enough for Material's 360 dp picker inside its dialog's margins. */
private const val OVERLAYS_WINDOW = "w420dp-h900dp"

/** 19 Aug 2026, 00:00 UTC: the date the other goldens use. */
private const val SELECTED_DAY_MILLIS = 1_787_097_600_000L

/** 10 Aug 2026, 00:00 UTC. */
private const val RANGE_START_MILLIS = 1_786_320_000_000L
