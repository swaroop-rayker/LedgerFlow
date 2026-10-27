package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG73 (the review half): an approval closed Review without a word, where the
 * Inbox row's own Approve says "Added to your ledger." It names the book now.
 *
 * Also E4's loading state: Review no longer draws an empty form while the
 * candidate is read, so there is no Approve to press on nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug73_ApprovalSaysWhereItWentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anApproval_saysWhereItWent_andCloses() {
        var done = 0
        rule.setContent {
            LfTheme {
                ReviewScreen(
                    state = ReviewUiState(
                        pendingId = "1",
                        loading = false,
                        ledger = LedgerType.DEBIT,
                        finished = true,
                    ),
                    onEvent = {},
                    onDone = { done++ },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Added to Expenses").assertExists()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun anUnreadBook_isStillNamed() {
        assertThat(approvedMessage(null)).isEqualTo("Added to your ledger.")
    }

    @Test
    fun whileLoading_noEmptyForm_andSkeletonsAfter300ms() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LfTheme {
                ReviewScreen(state = ReviewUiState(pendingId = "1"), onEvent = {}, onDone = {}, onBack = {})
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Amount").assertDoesNotExist()
        rule.onNodeWithText("Approve").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Loading").assertExists()
    }
}
