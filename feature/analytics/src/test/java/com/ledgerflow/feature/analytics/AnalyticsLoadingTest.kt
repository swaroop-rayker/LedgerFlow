package com.ledgerflow.feature.analytics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Analytics while its first snapshot is read (E5): still skeletons after
 * 300 ms. It used to be an empty-state card titled "Loading", which read as a
 * finding for as long as it stayed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AnalyticsLoadingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loading_isSkeletons_notAnEmptyCard() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LfTheme { LfScaffold { AnalyticsScreen(state = AnalyticsUiState(), onEvent = {}) } } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Loading").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Loading").assertExists()
        rule.onNodeWithText("Reading your ledger.").assertDoesNotExist()
    }
}
