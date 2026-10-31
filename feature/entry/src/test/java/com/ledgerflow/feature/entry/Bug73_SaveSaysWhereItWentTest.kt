package com.ledgerflow.feature.entry

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
 * BUG73 (the entry half): Add entry closed on a save without a word. It says
 * which book the entry went to, on the window's snackbar, as it closes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug73_SaveSaysWhereItWentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aSave_saysWhereItWent_andCloses() {
        var done = 0
        rule.setContent {
            LfTheme {
                EntryScreen(
                    state = EntryUiState(ledger = LedgerType.CREDIT, savedEntryId = "e1"),
                    onEvent = {},
                    onDone = { done++ },
                )
            }
        }
        rule.onNodeWithText("Saved to Income").assertExists()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun eachBook_isNamedAsTheLedgerNamesIt() {
        assertThat(savedMessage(LedgerType.DEBIT)).isEqualTo("Saved to Expenses")
        assertThat(savedMessage(LedgerType.CREDIT)).isEqualTo("Saved to Income")
    }
}
