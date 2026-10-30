package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
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
 * BUG78, found by the owner on the device: discarding a candidate in Review
 * said "Added to Expenses" (or "Added to your ledger." for an unread book).
 * BUG73's closing message read only that Review had finished, and a discard
 * finishes it too. It says "Discarded" now, and nothing about the ledger.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug78_DiscardDoesNotSayAddedTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aDiscard_saysDiscarded_notAdded() {
        var done = 0
        rule.setContent {
            LfTheme {
                ReviewScreen(
                    state = ReviewUiState(
                        pendingId = "1",
                        loading = false,
                        ledger = LedgerType.DEBIT,
                        finished = true,
                        discarded = true,
                    ),
                    onEvent = {},
                    onDone = { done++ },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Discarded").assertExists()
        rule.onNodeWithText("Added to Expenses").assertDoesNotExist()
        assertThat(done).isEqualTo(1)
    }

    /** An unread book is where the wrong line was "Added to your ledger." */
    @Test
    fun aDiscard_ofAnUnreadBook_saysNothingAboutTheLedger() {
        rule.setContent {
            LfTheme {
                ReviewScreen(
                    state = ReviewUiState(
                        pendingId = "1",
                        loading = false,
                        ledger = null,
                        bookIsUnread = true,
                        finished = true,
                        discarded = true,
                    ),
                    onEvent = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Discarded").assertExists()
        rule.onNodeWithText("Added to your ledger.").assertDoesNotExist()
    }
}
