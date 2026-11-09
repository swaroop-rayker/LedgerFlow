package com.ledgerflow.feature.tools.goals

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Savings goals (V2-7, V2-PLAN §4.1): the owner's mockups of 2026-11-09 as
 * goldens. 1x and 2x, RTL where a name sits beside an amount, and 1.15x, the
 * owner's text size, where the goal page's two inline actions must still fit
 * one line.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class GoalsGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        // In LfScaffold, as the shell hosts it: the screen paints no background of its own.
        LfTheme { LfScaffold { content() } }
    }

    @Composable
    private fun List(state: GoalsUiState) = GoalsScreen(state = state, onNewGoal = {}, onOpenGoal = {}, onBack = {})

    @Composable
    private fun Goal(state: GoalUiState) = GoalScreen(state = state, onEvent = {}, onEdit = {}, onBack = {})

    @Composable
    private fun Editor(state: GoalEditorUiState) = GoalEditorScreen(state = state, onEvent = {}, onBack = {})

    @Test fun list_1x() = capture("goals-list-1x", 1f) { List(GoalsPreviews.list) }

    @Config(qualifiers = PHONE_2X)
    @Test fun list_2x() = capture("goals-list-2x", 2f) { List(GoalsPreviews.list) }

    @Test fun list_rtl_1x() = capture("goals-list-rtl-1x", 1f, LayoutDirection.Rtl) { List(GoalsPreviews.list) }

    @Test fun list_115() = capture("goals-list-1.15x", FONT_SCALE_PHONE) { List(GoalsPreviews.list) }

    @Test fun empty_1x() = capture("goals-empty-1x", 1f) { List(GoalsPreviews.empty) }

    @Config(qualifiers = PHONE_2X)
    @Test fun empty_2x() = capture("goals-empty-2x", 2f) { List(GoalsPreviews.empty) }

    @Test fun goal_1x() = capture("goal-page-1x", 1f) { Goal(GoalsPreviews.goal) }

    @Config(qualifiers = PHONE_2X)
    @Test fun goal_2x() = capture("goal-page-2x", 2f) { Goal(GoalsPreviews.goal) }

    @Test fun goal_rtl_1x() = capture("goal-page-rtl-1x", 1f, LayoutDirection.Rtl) { Goal(GoalsPreviews.goal) }

    @Test fun goal_115() = capture("goal-page-1.15x", FONT_SCALE_PHONE) { Goal(GoalsPreviews.goal) }

    /** No category, nothing added by hand, too early to project. */
    @Test fun goalNew_1x() = capture("goal-page-new-1x", 1f) { Goal(GoalsPreviews.newGoal) }

    @Test fun editor_1x() = capture("goal-editor-1x", 1f) { Editor(GoalsPreviews.editor) }

    @Config(qualifiers = PHONE_2X)
    @Test fun editor_2x() = capture("goal-editor-2x", 2f) { Editor(GoalsPreviews.editor) }

    @Test fun editor_rtl_1x() = capture("goal-editor-rtl-1x", 1f, LayoutDirection.Rtl) { Editor(GoalsPreviews.editor) }

    @Test fun editorNew_1x() = capture("goal-editor-new-1x", 1f) { Editor(GoalsPreviews.newEditor) }

    // No golden of "Add money": a text field inside a dialog window never lets
    // Robolectric's Compose go idle (probed 2026-11-09: LfAmountField and
    // LfTextField alike, an empty dialog fine), which is also why the budget
    // editor has none. TESTING.md K2 checks that dialog on the phone.

    @Test fun deleteSheet_1x() = overlay("goal-delete-sheet-1x", 1f, GoalsPreviews.goal.copy(confirmingDelete = true))

    @Config(qualifiers = PHONE_2X)
    @Test fun deleteSheet_2x() = overlay("goal-delete-sheet-2x", 2f, GoalsPreviews.goal.copy(confirmingDelete = true))

    /**
     * A sheet is its own window: it ignores `captureScreenGolden`'s
     * density override, so the font scale goes through the configuration and
     * the whole screen is captured, with the time zone pinned to UTC as
     * `InboxImportGoldensTest` does.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun overlay(name: String, fontScale: Float, state: GoalUiState) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        val hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            rule.setContent { GoldenTheme.Provide { LfTheme { LfScaffold { Goal(state) } } } }
            rule.waitForIdle()
            captureScreenRoboImage(
                "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
                roborazziOptions = LfScreenshotOptions,
            )
        } finally {
            TimeZone.setDefault(hostZone)
        }
    }

    private companion object {
        /** The owner's phone runs text at 1.15. */
        const val FONT_SCALE_PHONE = 1.15f
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalsGoldensDarkTest : GoalsGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
