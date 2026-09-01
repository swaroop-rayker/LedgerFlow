package com.ledgerflow.feature.onboarding.notifications

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG17 — the explainer's title broke mid-word (SPEC.md §8, BUG9's family).
 *
 * Reported from the device: the heading rendered as "Notificatio" with a lone
 * "n" beneath it. Measured at the owner's settings — font scale 1.15, density
 * override 480 — the title node was **678px wide and 246px tall**, two lines of
 * `displayL` for a two-word string that should have taken one or wrapped
 * cleanly between the words.
 *
 * **This is not a BUG9 violation, and the distinction is the whole diagnosis.**
 * BUG9 governs *control* labels, and the control behaved exactly as §8 requires
 * it to: `LfButton` renders `maxLines = 1, softWrap = false`, so "Done" held its
 * natural width and refused to shrink. The title sat beside it in a
 * `Modifier.weight(1f)` column and absorbed the entire cost. So the countermeasure
 * worked and pushed the failure one element sideways, onto a `Text` that nothing
 * was guarding — which is why this needs its own named test rather than an extra
 * case in `Bug9_ControlLabelsNeverWrapTest`.
 *
 * `CLAUDE.md`'s design brief already prescribes the fix: *"The heading gets its
 * own line rather than competing with the buttons for it."* Every other screen
 * title in the app is one short word — "Home", "Export", "Categories" — so the
 * shared `Row(title.weight(1f), action)` pattern had never been asked to carry
 * a long one.
 *
 * ## What is asserted, and why not `lineCount`
 *
 * A heading is allowed to wrap — unlike a control label, two lines of title is
 * a legitimate layout. What is *not* allowed is a break inside a word. So the
 * assertion reads the real `TextLayoutResult` through the `GetTextLayoutResult`
 * semantics action, the same technique `Bug9_ControlLabelsNeverWrapTest` uses
 * for the same reason — the string was always correct, only its layout was
 * wrong — and checks every line boundary lands on whitespace.
 *
 * Font scale comes through [LocalDensity] rather than the device, so the check
 * runs at scales the phone is not in and leaves no system setting changed when
 * it fails.
 */
@RunWith(AndroidJUnit4::class)
class Bug17_ScreenTitleNeverBreaksMidWordTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(fontScale: Float, doneLabel: String = "Not now") {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = fontScale,
                ),
            ) {
                LfTheme {
                    NotificationAccessScreen(
                        state = NotificationAccessUiState(
                            listenerGranted = false,
                            postNotificationsGranted = false,
                            postNotificationsApplicable = true,
                            polled = true,
                        ),
                        onEvent = {},
                        doneLabel = doneLabel,
                    )
                }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.first()
    }

    /**
     * Every line break in [text] falls on whitespace.
     *
     * When Compose wraps at a word boundary the trailing space stays on the
     * preceding line, so the character before the break is whitespace. A
     * character-level break — the defect — has letters on both sides of it,
     * which is precisely what "Notificatio / n" is.
     */
    private fun assertNoMidWordBreak(text: String) {
        val layout = layoutOf(text)
        val laidOut = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (end <= 0 || end >= laidOut.length) continue
            val before = laidOut[end - 1]
            val after = laidOut[end]
            // assertWithMessage, not a bare assertThat on the message string --
            // the latter is an assertion that cannot fail, which is the exact
            // shape the P2-7 session found four times.
            assertWithMessage(
                "\"%s\" breaks mid-word at line %s, between '%s' and '%s'",
                laidOut,
                line,
                before,
                after,
            ).that(before.isWhitespace() || after.isWhitespace()).isTrue()
        }
    }

    /** The owner's actual settings, where this was reported. */
    @Test
    fun title_atTheReportedFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = REPORTED_FONT_SCALE)

        assertNoMidWordBreak("Notification capture")
    }

    @Test
    fun title_atDefaultFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = 1f)

        assertNoMidWordBreak("Notification capture")
    }

    /**
     * §9.6's requirement, and the scale the fix has to survive rather than
     * merely pass at the reported one.
     */
    @Test
    fun title_atLargestSupportedFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = LARGEST_SUPPORTED_FONT_SCALE)

        assertNoMidWordBreak("Notification capture")
    }

    /**
     * The Settings host, whose "Done" is **shorter** than first run's "Not now".
     *
     * It passed before the fix, and that is the point of keeping it: the same
     * title, the same font scale, and the only difference is three characters of
     * *button* label — which is the cleanest possible demonstration that the
     * heading's width was a function of the control beside it. If a future
     * change makes this one fail, the header has gone back to sharing a line.
     */
    @Test
    fun title_withTheShorterDoneLabel_keepsItsWordsWhole() {
        setScreen(fontScale = REPORTED_FONT_SCALE, doneLabel = "Done")

        assertNoMidWordBreak("Notification capture")
    }

    /**
     * The rest of the screen's prose, at the largest supported scale.
     *
     * The owner asked for the whole page to be checked, not only the heading.
     * These are `bodyL`/`bodyM` inside full-width cards, so they have far more
     * room than the title did — but "non-allowlisted" and "notifications" are
     * long words in narrow-ish containers, and the reported defect was a long
     * word in a narrow container.
     */
    @Test
    fun everyBlockOfProse_keepsItsWordsWhole() {
        setScreen(fontScale = LARGEST_SUPPORTED_FONT_SCALE)

        listOf(
            NOTIFICATION_PRIVACY_RULE,
            "What LedgerFlow reads",
            "Read payment notifications",
            "Tell you when something arrives",
        ).forEach(::assertNoMidWordBreak)
    }

    private companion object {
        /** The owner's device when BUG17 was reported. */
        const val REPORTED_FONT_SCALE = 1.15f
        const val LARGEST_SUPPORTED_FONT_SCALE = 2f
    }
}
