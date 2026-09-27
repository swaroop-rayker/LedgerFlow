package com.ledgerflow.core.ui.phrase

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every node of the phrase entry that carries a word is marked sensitive
 * (owner, 2026-09-27): TalkBack still reads it, other accessibility services
 * and screen-dump tools get an empty node (`phraseSecret`).
 *
 * Checked per node rather than on the container alone because Compose judges
 * events per node: a text field's typing events and a chip's announcement leak
 * through a flagged parent otherwise. The public BIP-39 test words only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SECRET_ROBOLECTRIC_SDK])
class PhraseSecretTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show() {
        composeRule.setContent {
            LfTheme {
                LfPhraseEntry(
                    words = listOf("abandon", "ability"),
                    draft = "ab",
                    suggestions = listOf("able", "about"),
                    draftIsUnknown = false,
                    requiredWordCount = 24,
                    onDraftChange = {},
                    onSuggestionTap = {},
                    onWordRemove = {},
                    onScanRequested = {},
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.isSecret(): Boolean =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsSensitiveData) == true

    @Test
    fun theEnteredWordChips_areSensitive() {
        show()
        val chips = composeRule.onAllNodes(hasContentDescription("Word ", substring = true))
        val count = chips.fetchSemanticsNodes().size
        assertWithMessage("entered-word chips found").that(count).isEqualTo(2)
        repeat(count) { assertWithMessage("chip $it").that(chips[it].isSecret()).isTrue() }
    }

    @Test
    fun theWordField_isSensitive() {
        show()
        val fields = composeRule.onAllNodes(hasSetTextAction())
        assertWithMessage("fields found").that(fields.fetchSemanticsNodes().size).isEqualTo(1)
        assertWithMessage("the word field").that(fields[0].isSecret()).isTrue()
    }

    @Test
    fun theSuggestions_areSensitive() {
        show()
        for (word in listOf("able", "about")) {
            val chip = composeRule.onAllNodes(hasText(word) and hasClickAction())[0]
            assertWithMessage("suggestion \"$word\"").that(chip.isSecret()).isTrue()
        }
    }

    /** The scan button names no word, and stays readable to every service. */
    @Test
    fun theScanButton_isNotHidden() {
        show()
        val scan = composeRule.onAllNodes(hasText("Scan the Recovery Kit") and hasClickAction())[0]
        assertWithMessage("scan button").that(scan.isSecret()).isFalse()
    }
}

private const val SECRET_ROBOLECTRIC_SDK = 34
