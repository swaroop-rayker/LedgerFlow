package com.ledgerflow.feature.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG72: a refused save was drawn at the very end of the scrolling form, under
 * the note and any line items, so on a form taller than the window it was off
 * screen and Save looked like it did nothing. It is in the pinned bar now,
 * above Save, in sight without scrolling.
 *
 * A short window and an itemised form, so the end of the form is below the
 * fold the way it is on a phone at font scale 2.0.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h480dp")
class Bug72_EntrySaveErrorIsInSightTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aRefusedSave_isOnScreen_andDismissable() {
        val events = mutableListOf<EntryEvent>()
        rule.setContent {
            LfTheme {
                EntryScreen(
                    state = EntryUiState(
                        amountText = "1,240.50",
                        amountMinor = 1_240_50L,
                        note = "Monthly staples",
                        itemised = true,
                        message = MESSAGE,
                    ),
                    onEvent = { events += it },
                    onDone = {},
                )
            }
        }
        rule.onNodeWithText(MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertThat(events).contains(EntryEvent.MessageDismissed)
    }

    private companion object {
        const val MESSAGE = "Choose a category before a subcategory."
    }
}
