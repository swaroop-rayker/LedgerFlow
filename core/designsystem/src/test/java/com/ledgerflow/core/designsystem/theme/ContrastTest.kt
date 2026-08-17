package com.ledgerflow.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Enforces the accessibility requirement in SPEC.md §9.6.
 *
 * "Contrast AA minimum" was stated as a requirement but nothing measured it --
 * and the originally specified `textTertiary` (#6B7285) failed it at 3.73:1
 * while being used as a text colour. That is exactly how an accessibility
 * requirement quietly becomes an accessibility preference.
 *
 * Each pair is asserted against the threshold appropriate to how the token is
 * actually used: body text needs 4.5:1; amounts are large text and need 3:1,
 * though in practice they clear the higher bar too.
 */
class ContrastTest {

    private fun assertPair(
        name: String,
        ratio: Double,
        threshold: Double,
    ) {
        assertThat("$name = ${"%.2f".format(ratio)}:1")
            .isEqualTo("$name = ${"%.2f".format(ratio)}:1")
        assertThat(ratio).isAtLeast(threshold)
    }

    @Test
    fun darkTheme_bodyTextMeetsAaNormal() {
        with(LfDarkColors) {
            assertPair(
                "textPrimary/surfaceBase",
                LfContrast.ratio(textPrimary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
            assertPair(
                "textSecondary/surfaceBase",
                LfContrast.ratio(textSecondary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
            // The token that was originally wrong. Guarded permanently.
            assertPair(
                "textTertiary/surfaceBase",
                LfContrast.ratio(textTertiary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
        }
    }

    @Test
    fun darkTheme_bodyTextOnRaisedSurfacesMeetsAaNormal() {
        with(LfDarkColors) {
            assertPair(
                "textPrimary/surfaceRaised",
                LfContrast.ratio(textPrimary, surfaceRaised),
                LfContrast.AA_NORMAL,
            )
            assertPair(
                "textSecondary/surfaceRaised",
                LfContrast.ratio(textSecondary, surfaceRaised),
                LfContrast.AA_NORMAL,
            )
        }
    }

    @Test
    fun darkTheme_amountColoursMeetAaLarge() {
        with(LfDarkColors) {
            assertPair("debit/surfaceBase", LfContrast.ratio(debit, surfaceBase), LfContrast.AA_LARGE)
            assertPair("credit/surfaceBase", LfContrast.ratio(credit, surfaceBase), LfContrast.AA_LARGE)
            assertPair("warn/surfaceBase", LfContrast.ratio(warn, surfaceBase), LfContrast.AA_LARGE)
            assertPair(
                "debit/surfaceRaised",
                LfContrast.ratio(debit, surfaceRaised),
                LfContrast.AA_LARGE,
            )
            assertPair(
                "credit/surfaceRaised",
                LfContrast.ratio(credit, surfaceRaised),
                LfContrast.AA_LARGE,
            )
        }
    }

    @Test
    fun darkTheme_accentIsReadableAsAButtonAndAsText() {
        with(LfDarkColors) {
            assertPair("onAccent/accent", LfContrast.ratio(onAccent, accent), LfContrast.AA_NORMAL)
            assertPair("accent/surfaceBase", LfContrast.ratio(accent, surfaceBase), LfContrast.AA_LARGE)
        }
    }

    @Test
    fun lightTheme_bodyTextMeetsAaNormal() {
        with(LfLightColors) {
            assertPair(
                "textPrimary/surfaceBase",
                LfContrast.ratio(textPrimary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
            assertPair(
                "textSecondary/surfaceBase",
                LfContrast.ratio(textSecondary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
            assertPair(
                "textTertiary/surfaceBase",
                LfContrast.ratio(textTertiary, surfaceBase),
                LfContrast.AA_NORMAL,
            )
        }
    }

    @Test
    fun lightTheme_amountColoursMeetAaLarge() {
        with(LfLightColors) {
            assertPair("debit/surfaceBase", LfContrast.ratio(debit, surfaceBase), LfContrast.AA_LARGE)
            assertPair("credit/surfaceBase", LfContrast.ratio(credit, surfaceBase), LfContrast.AA_LARGE)
            assertPair(
                "debit/surfaceRaised",
                LfContrast.ratio(debit, surfaceRaised),
                LfContrast.AA_LARGE,
            )
        }
    }

    @Test
    fun lightTheme_accentIsReadableAsAButton() {
        with(LfLightColors) {
            assertPair("onAccent/accent", LfContrast.ratio(onAccent, accent), LfContrast.AA_NORMAL)
        }
    }

    /** Guards the maths itself, so a broken formula cannot pass everything. */
    @Test
    fun contrastMaths_matchesKnownReferenceValues() {
        val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        val black = androidx.compose.ui.graphics.Color(0xFF000000)

        assertThat(LfContrast.ratio(white, black)).isWithin(0.01).of(21.0)
        assertThat(LfContrast.ratio(white, white)).isWithin(0.01).of(1.0)
    }
}
