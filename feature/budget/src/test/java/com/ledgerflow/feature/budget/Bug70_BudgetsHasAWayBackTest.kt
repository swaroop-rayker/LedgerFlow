package com.ledgerflow.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * BUG70: Budgets is a full-screen page with no bottom bar, and it had no
 * on-screen way out -- only the system Back gesture. It has "Done" now, as
 * Organise, Export and Deleted entries do.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug70_BudgetsHasAWayBackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun done_leavesTheScreen() {
        var left = 0
        rule.setContent {
            LfTheme {
                LfScaffold {
                    BudgetScreen(state = BudgetUiState(isLoading = false), onEvent = {}, onBack = { left++ })
                }
            }
        }
        rule.onNodeWithText("Done").performClick()
        assertThat(left).isEqualTo(1)
    }
}
