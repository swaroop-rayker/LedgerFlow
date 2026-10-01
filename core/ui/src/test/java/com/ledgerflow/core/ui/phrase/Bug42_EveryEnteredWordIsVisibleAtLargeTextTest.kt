package com.ledgerflow.core.ui.phrase

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG42: at font scale 2.0 the third word of the phrase vanished.**
 *
 * `LfPhraseEntry` laid the entered words out in fixed rows of three, on the
 * argument that this "survives a 2.0x font scale without a chip being
 * clipped". At phone width it did not: two chips filled the row and the third
 * was given **zero** width — not clipped, gone — so a person typing their 24
 * words at large text could not see every third one they had entered. Found
 * by P5 step 4's touch-target check on "Back up now" (the third chip measured
 * 0 × 54 dp). The same entry is on Recovery and restore.
 *
 * Rendered at **the width a host gives it**, not the screen's: "Back up now"
 * insets it by 24 dp a side (312 dp on a 360 dp phone), and the first version
 * of this test gave it the full 360 dp, where three chips still fit — it
 * passed on the unfixed code. 280 dp covers a host that also sits in a card.
 *
 * Only the public BIP-39 test vector's word is used.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it Robolectric measures text as zero wide,
// every chip came out 48 dp and this test passed on the unfixed layout.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class Bug42_EveryEnteredWordIsVisibleAtLargeTextTest {

    @get:Rule
    val rule = createComposeRule()

    private fun showWords(count: Int, fontScale: Float, hostWidthDp: Int = BACK_UP_NOW_WIDTH) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    LfPhraseEntry(
                        modifier = Modifier.width(hostWidthDp.dp),
                        words = List(count) { "abandon" },
                        draft = "",
                        suggestions = emptyList(),
                        draftIsUnknown = false,
                        requiredWordCount = 24,
                        onDraftChange = {},
                        onWordRemove = {},
                        onSuggestionTap = {},
                    )
                }
            }
        }
    }

    private fun assertEveryChipFullyVisible(count: Int) {
        val chips = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("Word ") }
            }
        assertWithMessage("entered-word chips found").that(chips.size).isEqualTo(count)
        chips.forEach { chip ->
            val label = chip.config.getOrNull(SemanticsProperties.ContentDescription)?.first()
            assertWithMessage("%s laid out width", label).that(chip.size.width).isGreaterThan(0)
            assertWithMessage("%s visible width", label)
                .that(chip.boundsInRoot.width).isEqualTo(chip.size.width.toFloat())
        }
    }

    @Test
    fun Bug42_threeWordsAtFontScale2_areAllVisible() {
        showWords(count = 3, fontScale = 2f)

        assertEveryChipFullyVisible(3)
    }

    /** The whole phrase, at the scale where it broke. */
    @Test
    fun allTwentyFourWordsAtFontScale2_areAllVisible() {
        showWords(count = 24, fontScale = 2f)

        assertEveryChipFullyVisible(24)
    }

    @Test
    fun allTwentyFourWordsAtFontScale1_areAllVisible() {
        showWords(count = 24, fontScale = 1f)

        assertEveryChipFullyVisible(24)
    }

    @Test
    fun inACardWidthHost_atFontScale2_everyWordIsVisible() {
        showWords(count = 24, fontScale = 2f, hostWidthDp = CARD_WIDTH)

        assertEveryChipFullyVisible(24)
    }

    private companion object {
        const val BACK_UP_NOW_WIDTH = 312
        const val CARD_WIDTH = 280
    }
}
