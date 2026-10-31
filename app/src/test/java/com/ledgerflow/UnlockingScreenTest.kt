package com.ledgerflow

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launch's "Unlocking" step (design-system step E2): its heading at once,
 * and after 10 s a line saying it is still going, rather than one word on its
 * own for however long the vault takes to open.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class UnlockingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun unlocking_saysSo_andAt10sSaysItIsStillGoing() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LfTheme { LoadingScreen() } }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText("Unlocking").assertExists()
        rule.mainClock.advanceTimeBy(9_000)
        rule.onNodeWithText(SLOW, substring = true).assertDoesNotExist()

        rule.mainClock.advanceTimeBy(1_100)
        rule.onNodeWithText(SLOW, substring = true).assertExists()
    }

    private companion object {
        const val SLOW = "Still unlocking"
    }
}
