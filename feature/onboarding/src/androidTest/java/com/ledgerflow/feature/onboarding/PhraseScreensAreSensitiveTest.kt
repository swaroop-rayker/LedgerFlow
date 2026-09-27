package com.ledgerflow.feature.onboarding

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding's phrase screens hide the words from everything but accessibility
 * tools, and from screen sharing (owner, 2026-09-27; `phraseSecret`).
 *
 * On the device because the half that matters most — the window's content
 * sensitivity, which makes Android 15+ blank the screen in a share or a
 * recording — is a platform flag Robolectric does not model. The public BIP-39
 * test word only.
 */
@RunWith(AndroidJUnit4::class)
class PhraseScreensAreSensitiveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: OnboardingUiState) {
        composeRule.setContent {
            LfTheme { OnboardingScreen(state = state, onEvent = {}, onGeneratePhrase = {}) }
        }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.isSecret(): Boolean =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsSensitiveData) == true

    @Test
    fun theRevealedPhrase_everyWordRowIsSensitive() {
        show(OnboardingUiState(step = OnboardingStep.PhraseDisplay, mnemonic = List(24) { WORD }, phraseRevealed = true))

        val rows = composeRule.onAllNodes(hasContentDescription("Word ", substring = true))
        val count = rows.fetchSemanticsNodes().size
        assertThat(count).isEqualTo(24)
        repeat(count) { assertWithMessage("word row $it").that(rows[it].isSecret()).isTrue() }
    }

    @Test
    fun theWordChallenge_everyFieldIsSensitive() {
        show(OnboardingUiState(step = OnboardingStep.WordChallenge, challengePositions = listOf(3, 11, 19)))

        val fields = composeRule.onAllNodes(hasSetTextAction())
        assertThat(fields.fetchSemanticsNodes().size).isEqualTo(3)
        repeat(3) { assertWithMessage("challenge field $it").that(fields[it].isSecret()).isTrue() }
    }

    /** Android 15+: the window is content-sensitive while the words are on it. */
    @Test
    fun theRevealedPhrase_isHiddenFromScreenSharing() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        show(OnboardingUiState(step = OnboardingStep.PhraseDisplay, mnemonic = List(24) { WORD }, phraseRevealed = true))

        assertThat(composeView().isContentSensitive).isTrue()
    }

    /** And not before the phrase is shown: the flag is tied to the words, not to onboarding. */
    @Test
    fun theHiddenPhrase_isNotBlanked() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        show(OnboardingUiState(step = OnboardingStep.PhraseDisplay, mnemonic = List(24) { WORD }, phraseRevealed = false))

        assertThat(composeView().isContentSensitive).isFalse()
    }

    /** The Compose host view — the one `sensitiveContent` marks. */
    private fun composeView(): View {
        var found: View? = null
        composeRule.runOnUiThread {
            fun walk(view: View) {
                if (view.javaClass.name.endsWith("AndroidComposeView")) found = view
                if (view is ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it)) }
            }
            walk(composeRule.activity.window.decorView)
        }
        return requireNotNull(found) { "no AndroidComposeView in the window" }
    }

    private companion object {
        const val WORD = "abandon"
    }
}
