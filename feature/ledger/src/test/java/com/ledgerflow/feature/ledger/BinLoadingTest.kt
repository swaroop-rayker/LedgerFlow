package com.ledgerflow.feature.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deleted entries while the bin is first read (design-system step E3): nothing
 * for 300 ms, then still skeletons, and never "Nothing deleted" for a bin
 * that has not been read yet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class BinLoadingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anUnreadBin_showsSkeletons_notEmpty() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LfTheme { BinScreen(state = BinUiState(isLoaded = false), onEvent = {}, onBack = {}) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Loading").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Loading").assertExists()
        rule.onNodeWithText("Nothing deleted").assertDoesNotExist()
    }
}
