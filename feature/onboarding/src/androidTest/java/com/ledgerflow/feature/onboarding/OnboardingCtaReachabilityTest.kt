package com.ledgerflow.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every onboarding step's primary action is on screen without scrolling, at
 * font scale 2.0 (SPEC.md §9.6).
 *
 * The CTAs used to be the last child of the scrolling `Column`. At scale 1.0
 * that reads as fine; at 2.0 the phrase step's twenty-four words push "I've
 * written them down" well past the fold, and onboarding is a **gate** — a user
 * who does not think to scroll cannot get into the app at all. This is the
 * assertion the previous arrangement fails.
 *
 * **Font scale is provided through [LocalDensity], not set on the device.** The
 * device's scale belongs to its owner, and a test that changes a system setting
 * leaves it changed when it fails. This also lets the check run at a scale the
 * device is not currently in.
 *
 * **No real phrase is involved anywhere here.** The screen is stateless
 * (CLAUDE.md §5), so the state is a fake and the twenty-four words are the
 * BIP-39 wordlist's first entry repeated. Nothing generates a mnemonic, nothing
 * touches a vault, and no test may ever put a real one on screen.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingCtaReachabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val LARGEST_SUPPORTED_FONT_SCALE = 2f

        /** Not a real word from a real phrase. See the class note. */
        const val PLACEHOLDER_WORD = "abandon"
    }

    private fun setScreen(state: OnboardingUiState) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = LARGEST_SUPPORTED_FONT_SCALE,
                ),
            ) {
                LfTheme {
                    OnboardingScreen(state = state, onEvent = {}, onGeneratePhrase = {})
                }
            }
        }
    }

    private fun assertCtaVisible(state: OnboardingUiState, label: String) {
        setScreen(state)
        composeRule.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun baseCurrencyStep_atLargestFontScale_ctaIsOnScreen() {
        assertCtaVisible(OnboardingUiState(), "Continue")
    }

    /**
     * The worst case, and the one that motivated the change: twenty-four words
     * stacked one per line is the tallest content onboarding ever shows.
     */
    @Test
    fun phraseStep_atLargestFontScale_ctaIsOnScreen() {
        assertCtaVisible(
            OnboardingUiState(
                step = OnboardingStep.PhraseDisplay,
                mnemonic = List(24) { PLACEHOLDER_WORD },
                phraseRevealed = true,
            ),
            "I've written them down",
        )
    }

    /**
     * Three text fields plus the error copy, with the keyboard's own inset
     * still to come. Disabled is fine — the point is that it is *there*, so the
     * user can see what completing the fields will unlock.
     */
    @Test
    fun wordChallengeStep_atLargestFontScale_ctaIsOnScreen() {
        assertCtaVisible(
            OnboardingUiState(
                step = OnboardingStep.WordChallenge,
                challengePositions = listOf(3, 11, 19),
                challengeError = true,
            ),
            "Confirm",
        )
    }

    @Test
    fun recoveryKitStep_atLargestFontScale_ctaIsOnScreen() {
        assertCtaVisible(OnboardingUiState(step = OnboardingStep.RecoveryKit), "Save as text file")
    }

    @Test
    fun backupLocationStep_atLargestFontScale_ctaIsOnScreen() {
        assertCtaVisible(
            OnboardingUiState(step = OnboardingStep.BackupLocation),
            "Choose a folder",
        )
    }

    /**
     * The word challenge has no skip, and never may (SPEC.md §7.4, CLAUDE.md
     * §7). Pinning the primary action moved every step's CTA into one `when`,
     * which is exactly the kind of edit that could add one by symmetry with the
     * Recovery Kit step's "Skip". Asserting the absence is cheap; discovering
     * it in a release is not.
     */
    @Test
    fun wordChallengeStep_hasNoSkipAnywhere() {
        setScreen(
            OnboardingUiState(
                step = OnboardingStep.WordChallenge,
                challengePositions = listOf(3, 11, 19),
            ),
        )

        composeRule.onAllNodesWithText("skip", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("later", substring = true, ignoreCase = true)
            .assertCountEquals(0)
    }
}
