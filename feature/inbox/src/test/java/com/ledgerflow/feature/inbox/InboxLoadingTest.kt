package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Inbox while its queue is first read (E4): still skeletons after 300 ms, not a blank list. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class InboxLoadingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anUnreadQueue_showsSkeletons() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LfTheme { InboxScreen(state = InboxUiState(), onEvent = {}, onReview = {}) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Loading").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Loading").assertExists()
    }
}
