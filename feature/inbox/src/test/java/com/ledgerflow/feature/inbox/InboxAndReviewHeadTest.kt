package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.model.LedgerType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step F5's capture batch, the parts a golden cannot show: the empty
 * Pending queue's way forward, and the book tag Review's amount card wears
 * only when the book is known (Law 2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class InboxAndReviewHeadTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anEmptyPendingQueue_offersAddEntry_andOpensIt() {
        var opened = 0
        rule.setContent {
            LfTheme {
                InboxScreen(
                    state = InboxUiState(loading = false),
                    onEvent = {},
                    onReview = {},
                    onAddEntry = { opened++ },
                )
            }
        }
        rule.onNodeWithText("Add an entry").performClick()
        assertThat(opened).isEqualTo(1)
    }

    /** Only on Pending: an empty Discarded list is not a reason to add an entry. */
    @Test
    fun anotherEmptyFilter_offersNothing() {
        rule.setContent {
            LfTheme {
                InboxScreen(
                    state = InboxUiState(loading = false, filter = InboxFilter.DISCARDED),
                    onEvent = {},
                    onReview = {},
                )
            }
        }
        rule.onNodeWithText("Add an entry").assertDoesNotExist()
    }

    /** A chip's count is said with its name, as the old "Pending · 3" label was. */
    @Test
    fun aFilterChip_saysItsCount() {
        rule.setContent {
            LfTheme {
                InboxScreen(
                    state = InboxUiState(
                        loading = false,
                        counts = mapOf(InboxFilter.PENDING to 3),
                    ),
                    onEvent = {},
                    onReview = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Pending, 3").assertExists()
    }

    @Test
    fun theAmountCard_namesAKnownBook() {
        rule.setContent {
            LfTheme { ReviewScreen(state = loaded(LedgerType.CREDIT), onEvent = {}, onDone = {}, onBack = {}) }
        }
        rule.onNodeWithText("Income").assertExists()
    }

    /** An unread book gets no tag: the Book row below asks instead of the card guessing. */
    @Test
    fun theAmountCard_namesNoBook_whenItIsUnread() {
        rule.setContent {
            LfTheme {
                ReviewScreen(
                    state = loaded(null).copy(bookIsUnread = true),
                    onEvent = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Expense").assertDoesNotExist()
        rule.onNodeWithText("Income").assertDoesNotExist()
    }

    private fun loaded(ledger: LedgerType?) = ReviewUiState(
        pendingId = "1",
        loading = false,
        ledger = ledger,
        amountText = "200.00",
        rawMerchantName = "RAMESH KUMAR",
        sourceLabel = "From an SMS",
        occurredAt = 1_787_810_214_627L,
    )
}
