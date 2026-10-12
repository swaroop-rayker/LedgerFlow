package com.ledgerflow.core.designsystem.component

import android.graphics.Insets
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * **BUG49: a full-screen destination paid for the system bars twice.**
 *
 * The app shell is an [LfScaffold] whose content -- the navigation host -- is
 * padded for the status and navigation bars with a plain `Modifier.padding`.
 * Every full-screen destination (entry form, Export, review, the bin…) is a
 * second [LfScaffold] inside it, and nothing told it the bars were already paid
 * for: its content was inset for the status bar again, and its pinned bar for
 * the navigation bar again. Measured on SM-S721B (2026-10-12, 3 px/dp): the
 * entry form's scrolling content began at y = 184, twice the 92 px status bar,
 * and 192 px lay between its Save button and the navigation bar where the bar's
 * own padding accounts for 48; Export's bar showed the same 144 px surplus.
 * No golden could see it, because Robolectric draws with no system bars --
 * which is why this test supplies some.
 *
 * Built the way the shell builds it: the outer content uses `Modifier.padding`,
 * not `consumeWindowInsets`, so the fix has to live in [LfScaffold] rather than
 * in whichever caller remembers.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug49_NestedScaffoldInsetsOnceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showNestedScaffolds() {
        rule.runOnUiThread { rule.activity.window.setDecorFitsSystemWindows(false) }
        rule.setContent {
            LfTheme {
                LfScaffold { shellPadding ->
                    Box(Modifier.padding(shellPadding)) {
                        LfScaffold(
                            bottomBar = { Box(Modifier.fillMaxWidth().height(BAR).testTag("bar")) },
                        ) { inner ->
                            Box(Modifier.padding(inner).fillMaxSize().testTag("content"))
                        }
                    }
                }
            }
        }
        val density = rule.activity.resources.displayMetrics.density
        val bars = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, px(STATUS, density), 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, px(NAVIGATION, density)))
            .build()
        rule.runOnUiThread { rule.activity.window.decorView.dispatchApplyWindowInsets(bars) }
        rule.waitForIdle()
    }

    private fun px(dp: Dp, density: Float) = (dp.value * density).roundToInt()

    @Test
    fun Bug49_theNestedContentIsInsetForTheStatusBarOnce() {
        showNestedScaffolds()
        val top = rule.onNodeWithTag("content").getBoundsInRoot().top
        assertWithMessage("content top").that(top).isEqualTo(STATUS)
    }

    @Test
    fun Bug49_theNestedBarIsInsetForTheNavigationBarOnce() {
        showNestedScaffolds()
        val root = rule.onRoot().getBoundsInRoot().bottom
        val bar = rule.onNodeWithTag("bar").getBoundsInRoot().bottom
        assertWithMessage("gap under the pinned bar").that(root - bar).isEqualTo(NAVIGATION)
    }

    private companion object {
        val STATUS = 30.dp
        val NAVIGATION = 48.dp
        val BAR = 40.dp
    }
}
