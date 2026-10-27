package com.ledgerflow.core.designsystem.theme

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The step-E1 feedback parts are readable in both themes (SPEC §9.6).
 *
 * A notice puts primary text on its tone's faint fill; a field's error line is
 * danger-coloured text on the page; a progress bar's fill has to be told apart
 * from its track. Each is measured against the threshold its use needs: 4.5:1
 * for text, 3:1 for a non-text part someone has to see (WCAG 1.4.11).
 */
class NoticeContrastTest {

    private val themes = mapOf("dark" to LfDarkColors, "light" to LfLightColors)

    private fun assertAtLeast(name: String, ratio: Double, threshold: Double) {
        assertWithMessage("$name = ${"%.2f".format(ratio)}:1").that(ratio).isAtLeast(threshold)
    }

    @Test
    fun noticeText_onEveryToneFill_meetsAaNormal() {
        themes.forEach { (theme, colors) ->
            with(colors.status) {
                mapOf(
                    "info" to infoSubtle,
                    "success" to successSubtle,
                    "warning" to warningSubtle,
                    "error" to dangerSubtle,
                ).forEach { (tone, fill) ->
                    assertAtLeast(
                        "$theme: text.primary on $tone fill",
                        LfContrast.ratio(colors.text.primary, fill),
                        LfContrast.AA_NORMAL,
                    )
                }
            }
        }
    }

    @Test
    fun fieldErrorLine_onThePage_meetsAaNormal() {
        themes.forEach { (theme, colors) ->
            assertAtLeast(
                "$theme: status.danger on surface.canvas",
                LfContrast.ratio(colors.status.danger, colors.surface.canvas),
                LfContrast.AA_NORMAL,
            )
            assertAtLeast(
                "$theme: status.danger on surface.raised",
                LfContrast.ratio(colors.status.danger, colors.surface.raised),
                LfContrast.AA_NORMAL,
            )
        }
    }

    @Test
    fun progressFill_againstItsTrack_meetsNonTextContrast() {
        themes.forEach { (theme, colors) ->
            mapOf(
                "accent" to colors.action.primary,
                "positive" to colors.status.success,
                "warning" to colors.status.warning,
            ).forEach { (tone, fill) ->
                assertAtLeast(
                    "$theme: $tone fill on chart.track",
                    LfContrast.ratio(fill, colors.chart.track),
                    LfContrast.AA_LARGE,
                )
            }
        }
    }
}
