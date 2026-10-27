package com.ledgerflow.feature.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E5: after a confirmed delete the Ledger says where the entry went and offers
 * Undo on the window's snackbar; the Undo is reported back, so the entry can
 * be put back from the book it left.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class LedgerUndoSnackbarTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aDelete_offersUndo_andReportsIt() {
        val events = mutableListOf<LedgerEvent>()
        rule.setContent {
            LfTheme {
                LfScaffold {
                    LedgerScreen(
                        state = LedgerUiState(
                            isLoaded = true,
                            undoable = LedgerUndo(LedgerType.DEBIT, "entry-1", "₹69.00 · Transport"),
                        ),
                        entries = flowOf(PagingData.empty()),
                        onEvent = { events += it },
                        onOpenDraft = {},
                        onReviewCandidate = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Moved to Deleted entries").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertThat(events).contains(LedgerEvent.UndoDelete)
    }
}
