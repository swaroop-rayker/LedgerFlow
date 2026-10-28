package com.ledgerflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Method
import org.junit.Test

/**
 * The bundled font is actually applied (SPEC.md §9.2).
 *
 * Every failure this guards against is silent. A style that forgets its
 * `fontFamily` renders in the platform default and simply looks like a slightly
 * different screen; a weight that was never registered gets synthesized from
 * Regular rather than drawn from Inter's own master, and looks like a slightly
 * heavier one. Neither throws, and neither shows up in a diff — which is why
 * they are asserted structurally rather than left to a reviewer's eye.
 *
 * The style lists are gathered by reflection, not written out. A tenth
 * [LfTypography] style, or a sixteenth Material one arriving with a BOM bump,
 * is then covered on the day it appears instead of on the day someone notices.
 */
class LfFontsTest {

    /** Every zero-argument getter on [type] that yields a [TextStyle]. */
    private fun styleGettersOf(type: Class<*>): List<Method> =
        type.methods.filter {
            it.returnType == TextStyle::class.java && it.parameterCount == 0
        }

    /**
     * Kotlin `internal` members survive into bytecode with a mangled name, so
     * Material's `displayLargeEmphasized` and friends show up in reflection
     * alongside the fifteen public styles. They are not settable through the
     * public constructor — Material derives them — so they are not this test's
     * business, and `$` is what separates the two sets.
     */
    private fun publicStyleGettersOf(type: Class<*>): List<Method> =
        styleGettersOf(type).filter { '$' !in it.name }

    private fun styleOf(method: Method, instance: Any): TextStyle =
        method.invoke(instance) as TextStyle

    /**
     * Every style is one of the two bundled families: amounts in Plex Mono
     * (ADR-0031), everything else in Inter. Never `FontFamily.Default`.
     */
    @Test
    fun everyAppStyleUsesABundledFamily() {
        val getters = styleGettersOf(LfTypography::class.java)
        // A guard on the guard: if reflection ever stops finding the styles,
        // every assertion below passes vacuously.
        assertThat(getters).isNotEmpty()

        // By value, not by getter name: a data class also exposes `componentN()`.
        val amounts = setOf(LfDefaultTypography.amountL, LfDefaultTypography.amountM)
        getters.forEach { getter ->
            val style = styleOf(getter, LfDefaultTypography)
            val expected = if (style in amounts) LfFigureFontFamily else LfFontFamily
            assertThat(style.fontFamily).isEqualTo(expected)
        }
    }

    @Test
    fun everyWeightTheTypeScaleAsksForIsRegistered() {
        val styles = styleGettersOf(LfTypography::class.java).map { styleOf(it, LfDefaultTypography) }
        val words = styles.filter { it.fontFamily == LfFontFamily }.mapNotNull { it.fontWeight }.toSet()
        val figures = styles.filter { it.fontFamily == LfFigureFontFamily }.mapNotNull { it.fontWeight }.toSet()

        assertThat(words).isNotEmpty()
        assertThat(figures).isNotEmpty()
        assertThat(LfFontWeights).containsAtLeastElementsIn(words)
        assertThat(LfFigureFontWeights).containsAtLeastElementsIn(figures)
    }

    /**
     * "Bold text" adds about 300 to every weight. Plex Mono's heaviest master is
     * Bold, so 800 and 900 must be registered (at the Bold file) or Compose
     * synthesizes a fake Bold for every amount under that setting.
     */
    @Test
    fun plexMono_answersTheBoldTextAdjustmentWithoutSynthesis() {
        listOf(FontWeight.ExtraBold, FontWeight.Black).forEach {
            assertThat(LfFigureFontWeights).contains(it)
        }
    }

    @Test
    fun everyMaterialStyleUsesTheBundledFamily() {
        val getters = publicStyleGettersOf(Typography::class.java)
        assertThat(getters).isNotEmpty()

        getters.forEach { getter ->
            assertThat(styleOf(getter, LfMaterialTypography).fontFamily)
                .isEqualTo(LfFontFamily)
        }
    }

    /**
     * Material's own default is the thing being replaced. If this ever passes
     * without the mapping, the other Material assertion has stopped meaning
     * anything.
     */
    @Test
    fun materialsUntouchedDefaultDoesNotUseTheBundledFamily() {
        val default = Typography()

        assertThat(default.bodyMedium.fontFamily).isNotEqualTo(LfFontFamily)
    }
}
