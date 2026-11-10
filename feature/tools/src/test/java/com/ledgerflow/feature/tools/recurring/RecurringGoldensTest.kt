package com.ledgerflow.feature.tools.recurring

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
 * Recurring (V2-7): the owner's mockups of 2026-11-09 as goldens. 1x and 2x,
 * RTL where a name sits beside an amount, and 1.15x, the owner's text size,
 * where the form's five repeat chips and the suggestions' two actions must
 * wrap as whole controls.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class RecurringGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { LfScaffold { content() } } }

    @Composable
    private fun List(state: RecurringListUiState) = RecurringScreen(
        state = state,
        onEvent = {},
        onNew = {},
        onAddSuggestion = { _, _ -> },
        onOpen = {},
        onBack = {},
    )

    @Composable
    private fun Item(state: RecurringItemUiState) =
        RecurringItemScreen(state = state, onEvent = {}, onEdit = {}, onBack = {})

    @Composable
    private fun Editor(state: RecurringEditorUiState) = RecurringEditorScreen(state = state, onEvent = {}, onBack = {})

    @Test fun list_1x() = capture("recurring-list-1x", 1f) { List(RecurringPreviews.list) }

    @Config(qualifiers = PHONE_2X)
    @Test fun list_2x() = capture("recurring-list-2x", 2f) { List(RecurringPreviews.list) }

    @Test fun list_rtl_1x() = capture("recurring-list-rtl-1x", 1f, LayoutDirection.Rtl) { List(RecurringPreviews.list) }

    @Test fun list_115() = capture("recurring-list-1.15x", FONT_SCALE_PHONE) { List(RecurringPreviews.list) }

    @Test fun empty_1x() = capture("recurring-empty-1x", 1f) { List(RecurringPreviews.empty) }

    @Test fun item_1x() = capture("recurring-item-1x", 1f) { Item(RecurringPreviews.item) }

    @Config(qualifiers = PHONE_2X)
    @Test fun item_2x() = capture("recurring-item-2x", 2f) { Item(RecurringPreviews.item) }

    @Test fun item_rtl_1x() = capture("recurring-item-rtl-1x", 1f, LayoutDirection.Rtl) { Item(RecurringPreviews.item) }

    @Test fun editor_1x() = capture("recurring-editor-1x", 1f) { Editor(RecurringPreviews.editor) }

    @Config(qualifiers = PHONE_2X)
    @Test fun editor_2x() = capture("recurring-editor-2x", 2f) { Editor(RecurringPreviews.editor) }

    @Test fun editor_115() = capture("recurring-editor-1.15x", FONT_SCALE_PHONE) { Editor(RecurringPreviews.editor) }

    @Test fun editorEveryNDays_1x() = capture("recurring-editor-every-n-days-1x", 1f) {
        Editor(RecurringPreviews.editorEveryNDays)
    }

    @Test fun deleteSheet_1x() = sheet("recurring-delete-sheet-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun deleteSheet_2x() = sheet("recurring-delete-sheet-2x", 2f)

    /** A sheet is its own window: the whole screen, the font scale through the configuration, UTC. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun sheet(name: String, fontScale: Float) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        val hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            rule.setContent {
                GoldenTheme.Provide {
                    LfTheme { LfScaffold { Item(RecurringPreviews.item.copy(confirmingDelete = true)) } }
                }
            }
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
class RecurringGoldensDarkTest : RecurringGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
