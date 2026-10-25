package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Method
import org.junit.Test

/**
 * Material's theme is mapped completely from LedgerFlow's tokens
 * (DESIGN_SYSTEM_PLAN §6.4, step A2).
 *
 * A role left at Material's default is invisible until a Material widget draws
 * with it: the lavender date pickers, and BUG63's vanishing off switch. So the
 * roles are found by reflection rather than listed: a fiftieth colour role or a
 * sixth shape arriving with a Material upgrade fails here on the day it
 * appears, the same way `LfFontsTest` guards Material's typography.
 */
class LfMaterialThemeTest {

    /**
     * Every colour role's getter. `Color` is a value class over `Long`, so a
     * getter returns a primitive `long` under a mangled name
     * (`getPrimary-0d7_KjU`); nothing else on [ColorScheme] does.
     */
    private val colorRoles: List<Method> =
        ColorScheme::class.java.methods.filter {
            it.returnType == java.lang.Long.TYPE && it.parameterCount == 0 && it.name.startsWith("get")
        }

    private fun roleName(getter: Method): String = getter.name.removePrefix("get").substringBefore('-')

    private fun valueOf(getter: Method, scheme: ColorScheme): Long = getter.invoke(scheme) as Long

    /**
     * Roles where LedgerFlow's own value happens to equal Material's default.
     * Listed by name so a coincidence is a decision, not a hole: the black
     * scrim in both themes, and in the light theme the white on the accent
     * button and the white of the lowest surface, which are `LfLightColors`'
     * `onAccent` and `surfaceRaised`.
     */
    private val darkSameAsMaterial = setOf("Scrim")
    private val lightSameAsMaterial = setOf("Scrim", "OnPrimary", "SurfaceContainerLowest")

    @Test
    fun reflectionFindsEveryColourRole() {
        // A guard on the guard: if this ever finds nothing, the tests below pass
        // vacuously. 48 is Material 1.4.0's count.
        assertThat(colorRoles.size).isAtLeast(MATERIAL_1_4_COLOUR_ROLES)
    }

    @Test
    fun noDarkColourRoleIsLeftAtMaterialsDefault() {
        assertNoRoleLeftAtDefault(LfDarkColorScheme, darkColorScheme(), darkSameAsMaterial)
    }

    @Test
    fun noLightColourRoleIsLeftAtMaterialsDefault() {
        assertNoRoleLeftAtDefault(LfLightColorScheme, lightColorScheme(), lightSameAsMaterial)
    }

    private fun assertNoRoleLeftAtDefault(ours: ColorScheme, materials: ColorScheme, onPurpose: Set<String>) {
        val leftAtDefault = colorRoles
            .filter { roleName(it) !in onPurpose }
            .filter { valueOf(it, ours) == valueOf(it, materials) }
            .map(::roleName)
        assertThat(leftAtDefault).isEmpty()
    }

    /** Every role is one of the twelve tokens or a blend of two: no new colour enters here. */
    @Test
    fun theTokensThatCarryMeaningAreWhereMaterialLooksForThem() {
        listOf(LfDarkColors to LfDarkColorScheme, LfLightColors to LfLightColorScheme).forEach { (tokens, scheme) ->
            assertThat(scheme.primary).isEqualTo(tokens.accent)
            assertThat(scheme.background).isEqualTo(tokens.surfaceBase)
            assertThat(scheme.surface).isEqualTo(tokens.surfaceRaised)
            assertThat(scheme.onSurface).isEqualTo(tokens.textPrimary)
            assertThat(scheme.outlineVariant).isEqualTo(tokens.outline)
            // Date pickers draw on surfaceContainerHigh: the same surface LfDialog uses.
            assertThat(scheme.surfaceContainerHigh).isEqualTo(tokens.surfaceOverlay)
        }
    }

    @Test
    fun everyShapeComesFromTheRadiusTokens() {
        // `$` marks Material's internal shapes (`ExtraExtraLarge$material3`
        // and friends): derived, not settable, so not this test's business, the
        // same cut LfFontsTest makes for typography.
        val getters = Shapes::class.java.methods.filter {
            CornerBasedShape::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0 && '$' !in it.name
        }
        // Exactly Material 1.4.0's five. A new one fails here until it is mapped.
        assertThat(getters.map { it.name.removePrefix("get") })
            .containsExactly("ExtraSmall", "Small", "Medium", "Large", "ExtraLarge")

        val spacing = LfSpacing()
        val expected = mapOf(
            "ExtraSmall" to RoundedCornerShape(spacing.cornerSmall),
            "Small" to RoundedCornerShape(spacing.cornerSmall),
            "Medium" to RoundedCornerShape(spacing.cornerMedium),
            "Large" to RoundedCornerShape(spacing.cornerLarge),
            "ExtraLarge" to RoundedCornerShape(spacing.cornerLarge),
        )
        getters.forEach { getter ->
            assertThat(getter.invoke(LfMaterialShapes)).isEqualTo(expected.getValue(getter.name.removePrefix("get")))
        }
    }

    /** Material's own extra-large corner is the 28 dp being replaced; if it ever matches, the test above means nothing. */
    @Test
    fun materialsDefaultExtraLargeIsNotOurs() {
        assertThat(Shapes().extraLarge).isNotEqualTo(LfMaterialShapes.extraLarge)
    }

    private companion object {
        const val MATERIAL_1_4_COLOUR_ROLES = 48
    }
}
