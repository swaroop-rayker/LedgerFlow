package com.ledgerflow.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG68: Budgets set "Budget saved", "Budget deleted" and "Could not delete"
 * in its state, and nothing drew them -- so saving, deleting and failing to
 * delete all looked the same, which is to say like nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug68_BudgetMessagesAreShownTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aMessage_isShown_andThenMarkedShown() {
        val events = mutableListOf<BudgetEvent>()
        rule.setContent {
            LfTheme {
                LfScaffold {
                    BudgetScreen(
                        state = BudgetUiState(isLoading = false, message = "Could not delete"),
                        onEvent = { events += it },
                        onBack = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Could not delete").assertExists()
        assertThat(events).contains(BudgetEvent.MessageShown)
    }
}
