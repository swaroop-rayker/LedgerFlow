package com.ledgerflow.core.designsystem.pattern

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * A page with a way back draws the arrow-and-title bar (step F5): the arrow
 * is one button named "Back", the title is the page's heading, and the
 * template's old "Back" text button is gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class LfBackTitleBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theArrowGoesBack_andTheTitleIsAHeading() {
        var back = 0
        rule.setContent {
            LfTheme { LfSettingsPage(title = "Appearance", onBack = { back++ }) {} }
        }
        rule.onNodeWithContentDescription("Back").performClick()
        assertThat(back).isEqualTo(1)
        rule.onNodeWithText("Appearance").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Back").assertDoesNotExist()
    }
}
