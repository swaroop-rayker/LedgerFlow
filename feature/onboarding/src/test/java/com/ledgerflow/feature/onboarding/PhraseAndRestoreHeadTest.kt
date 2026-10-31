package com.ledgerflow.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.feature.onboarding.restore.RestoreScreen
import com.ledgerflow.feature.onboarding.restore.RestoreUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step F5's first-run batch, the parts a golden cannot show: the phrase in two
 * columns is still twenty-four separately named words, read across; and
 * Restore's back arrow leaves, except while a restore runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class PhraseAndRestoreHeadTest {

    private companion object {
        const val LARGE_FONT = 2f
    }

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun thePhrase_isTwentyFourNamedWords_readAcross() {
        val words = List(24) { "word$it" }
        rule.setContent {
            LfTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.PhraseDisplay,
                        mnemonic = words,
                        phraseRevealed = true,
                    ),
                    onEvent = {},
                    onGeneratePhrase = {},
                )
            }
        }
        words.forEachIndexed { index, word ->
            rule.onNodeWithContentDescription("Word ${index + 1}: $word").assertExists()
        }
        val one = rule.onNodeWithContentDescription("Word 1: word0").getBoundsInRoot()
        val two = rule.onNodeWithContentDescription("Word 2: word1").getBoundsInRoot()
        val three = rule.onNodeWithContentDescription("Word 3: word2").getBoundsInRoot()
        // 1 and 2 share a row, 2 after 1; 3 starts the next row.
        assertThat(two.top).isEqualTo(one.top)
        assertThat(two.left).isGreaterThan(one.left)
        assertThat(three.top).isGreaterThan(one.top)
        assertThat(three.left).isEqualTo(one.left)
    }

    /**
     * At font scale 2.0 two columns broke "abandon" into "aband / on" (the
     * first 2x golden), and half a word can be copied down as the word. When
     * a word would not fit half the width, the phrase is one column.
     */
    @Test
    fun atLargeText_thePhraseIsOneColumn_soNoWordBreaks() {
        val words = List(24) { "abandon" }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGE_FONT)) {
                LfTheme {
                    OnboardingScreen(
                        state = OnboardingUiState(
                            step = OnboardingStep.PhraseDisplay,
                            mnemonic = words,
                            phraseRevealed = true,
                        ),
                        onEvent = {},
                        onGeneratePhrase = {},
                    )
                }
            }
        }
        val one = rule.onNodeWithContentDescription("Word 1: abandon").getBoundsInRoot()
        val two = rule.onNodeWithContentDescription("Word 2: abandon").getBoundsInRoot()
        assertThat(two.top).isGreaterThan(one.top)
        assertThat(two.left).isEqualTo(one.left)
    }

    @Test
    fun restoresArrow_goesBack() {
        var back = 0
        rule.setContent {
            LfTheme {
                RestoreScreen(
                    state = RestoreUiState(entry = PhraseEntry(requiredWordCount = 24)),
                    resuming = false,
                    onEvent = {},
                    onBack = { back++ },
                )
            }
        }
        rule.onNodeWithContentDescription("Back").performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test
    fun restoresArrow_waitsForARestoreInProgress() {
        rule.setContent {
            LfTheme {
                RestoreScreen(
                    state = RestoreUiState(entry = PhraseEntry(requiredWordCount = 24), isWorking = true),
                    resuming = false,
                    onEvent = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
    }
}
