package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfErrorNotice
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfProgressBar
import com.ledgerflow.core.designsystem.component.LfProgressTone
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFlowStep
import com.ledgerflow.core.designsystem.theme.LfTheme
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
 * Step E1's notice, progress and field-error parts, at 1.0 and 2.0 and in RTL
 * (DESIGN_SYSTEM_PLAN §5.1–§5.3), through `captureScreenGolden` so each also
 * passes its touch-target, label and clipping checks.
 *
 * No screen uses them yet; E2–E6 move each screen's own banner, error text and
 * progress here, and these are the reference those moves are held to.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfFeedbackScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { content() } }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        LfScaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.space.screenEdge),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap),
            ) { content() }
        }
    }

    @Composable
    private fun Notices() = Page {
        LfNotice(
            tone = LfNoticeTone.Info,
            title = "Capture is reconnecting",
            body = "Android restarts the listener after an update. Nothing is lost meanwhile.",
        )
        LfNotice(
            tone = LfNoticeTone.Success,
            title = "Backup written",
            body = "LedgerFlow-2026-10-27.lfbk, checked after writing.",
        )
        LfNotice(
            tone = LfNoticeTone.Warning,
            title = "Notification access is off",
            body = "Payments that only send a notification are being missed.",
            actionLabel = "Turn on",
            onAction = {},
        )
        LfErrorNotice(
            title = "Couldn't load your budgets",
            body = "Your data is safe. Try again.",
            actionLabel = "Retry",
            onAction = {},
        )
    }

    @Composable
    private fun Progress() = Page {
        Text("Accent, 0 %", style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfProgressBar(fraction = 0f)
        Text("Accent, 40 %", style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfProgressBar(fraction = 0.4f)
        Text("Positive, 100 %", style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfProgressBar(fraction = 1f, tone = LfProgressTone.Positive)
        Text("Warning, past the end", style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfProgressBar(fraction = 1.3f, tone = LfProgressTone.Warning)
    }

    @Composable
    private fun FieldError() = Page {
        LfTextField(value = "", onValueChange = {}, label = "Name", error = "A name can't be blank.")
        LfTextField(
            value = "Groceries",
            onValueChange = {},
            label = "Name",
            error = "There's already a category called Groceries.",
        )
        LfTextField(value = "Rent", onValueChange = {}, label = "Name", supportingText = "Shown in lists and charts.")
    }

    @Composable
    private fun RestoreInProgress() = LfFlowStep(
        title = "Restoring your backup",
        body = "Checking each table, then writing it to this phone.",
        progress = { LfProgressBar(fraction = 0.62f, contentDescription = "Restoring, 62 percent") },
        safeNote = "Your current data is untouched until the restore has been checked.",
        actionBar = { LfActionBar { LfButton(text = "Cancel", onClick = {}) } },
    ) {}

    @Test fun notices_1x() = capture("feedback-notices-1x") { Notices() }

    @Config(qualifiers = PHONE_2X)
    @Test fun notices_2x() = capture("feedback-notices-2x", fontScale = 2f) { Notices() }

    @Test fun notices_1x_rtl() = capture("feedback-notices-1x-rtl", direction = LayoutDirection.Rtl) { Notices() }

    @Test fun progress_1x() = capture("feedback-progress-1x") { Progress() }

    @Test fun progress_1x_rtl() = capture("feedback-progress-1x-rtl", direction = LayoutDirection.Rtl) { Progress() }

    @Test fun fieldError_1x() = capture("feedback-field-error-1x") { FieldError() }

    @Config(qualifiers = PHONE_2X)
    @Test fun fieldError_2x() = capture("feedback-field-error-2x", fontScale = 2f) { FieldError() }

    @Test fun flowStepProgress_1x() = capture("feedback-flow-progress-1x") { RestoreInProgress() }

    @Config(qualifiers = PHONE_2X)
    @Test fun flowStepProgress_2x() = capture("feedback-flow-progress-2x", fontScale = 2f) { RestoreInProgress() }

    /** A screen reader hears each error line as its field's error, not as more text. */
    @Test
    fun fieldError_isTheFieldsError_forScreenReaders() {
        rule.setContent { LfTheme { FieldError() } }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertCountEquals(2)
    }

    /** Four notices; the three that report news are announced, information is not. */
    @Test
    fun notices_announceSuccessWarningAndError_notInformation() {
        rule.setContent { LfTheme { Notices() } }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(3)
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfFeedbackScreenshotDarkTest : LfFeedbackScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
