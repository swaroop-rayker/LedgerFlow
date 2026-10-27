package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfContentState
import com.ledgerflow.core.designsystem.component.LfEmptyKind
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LoadingPhase
import com.ledgerflow.core.designsystem.component.StateContent
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
 * `LfStateContent`'s states as a screen draws them (DESIGN_SYSTEM_PLAN §5.1,
 * step E1), at 1.0 and 2.0 and in RTL, through `captureScreenGolden`.
 *
 * Loading starts at its phase rather than waiting it out, so the skeletons and
 * the ten-second line are each captured as they look.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfStatesScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { content() } }

    @Composable
    private fun Page(state: LfContentState, phase: LoadingPhase = LoadingPhase.Skeleton) {
        LfScaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.space.screenEdge),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap),
            ) {
                Text("Budgets", style = LfTheme.type.display, color = LfTheme.colors.text.primary)
                StateContent(
                    state = state,
                    modifier = Modifier,
                    slowMessage = "Still adding up this month. Nothing is lost; this is taking longer than usual.",
                    skeleton = { com.ledgerflow.core.designsystem.component.LfSkeletonList() },
                    startPhase = phase,
                ) {}
            }
        }
    }

    private val noMatches = LfContentState.Empty(
        kind = LfEmptyKind.NoMatches,
        title = "Nothing matched these filters",
        body = "Two filters are on. Clear them to see every budget.",
        actionLabel = "Clear filters",
        onAction = {},
    )
    private val error = LfContentState.Error(
        title = "Couldn't load your budgets",
        body = "Your data is safe; reading it failed. Try again.",
        onRetry = {},
    )
    private val unavailable = LfContentState.Unavailable(
        title = "Camera access is off",
        body = "Scanning a receipt needs the camera. You can still add the entry by hand.",
        fixLabel = "Allow camera",
        onFix = {},
    )

    @Test fun skeleton_1x() = capture("state-loading-1x") { Page(LfContentState.Loading) }

    @Test fun slow_1x() = capture("state-loading-slow-1x") { Page(LfContentState.Loading, LoadingPhase.Slow) }

    @Config(qualifiers = PHONE_2X)
    @Test fun slow_2x() = capture("state-loading-slow-2x", fontScale = 2f) {
        Page(LfContentState.Loading, LoadingPhase.Slow)
    }

    @Test fun noMatches_1x() = capture("state-empty-no-matches-1x") { Page(noMatches) }

    @Config(qualifiers = PHONE_2X)
    @Test fun noMatches_2x() = capture("state-empty-no-matches-2x", fontScale = 2f) { Page(noMatches) }

    @Test fun error_1x() = capture("state-error-1x") { Page(error) }

    @Test fun error_1x_rtl() = capture("state-error-1x-rtl", direction = LayoutDirection.Rtl) { Page(error) }

    @Test fun unavailable_1x() = capture("state-unavailable-1x") { Page(unavailable) }

    @Config(qualifiers = PHONE_2X)
    @Test fun unavailable_2x() = capture("state-unavailable-2x", fontScale = 2f) { Page(unavailable) }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfStatesScreenshotDarkTest : LfStatesScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
