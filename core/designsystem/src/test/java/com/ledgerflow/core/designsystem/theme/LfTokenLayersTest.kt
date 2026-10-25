package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Step B1 moved the theme onto primitive and semantic layers without changing
 * a single value (DESIGN_SYSTEM_PLAN §4.1, §7).
 *
 * The hex values below are what `LfColors.kt` held before B1, written out on
 * purpose: if a role is ever re-pointed at a different primitive by mistake,
 * this fails, where a test comparing the roles with the aliases would not (both
 * would move together). They change only with a deliberate palette change
 * (step F2), and then this test changes in the same commit.
 */
@Suppress("DEPRECATION") // The aliases are what this test pins to the roles.
class LfTokenLayersTest {

    private val darkBeforeB1 = mapOf(
        "surfaceBase" to 0xFF15171C, "surfaceRaised" to 0xFF1D2027, "surfaceOverlay" to 0xFF252932,
        "outline" to 0xFF333846, "textPrimary" to 0xFFE8EAF0, "textSecondary" to 0xFF9AA1B4,
        "textTertiary" to 0xFF7F8798, "accent" to 0xFF6E8BFF, "onAccent" to 0xFF0B1020,
        "debit" to 0xFFFF7A85, "credit" to 0xFF5FD0A6, "warn" to 0xFFF2B457,
    )

    private val lightBeforeB1 = mapOf(
        "surfaceBase" to 0xFFF7F8FA, "surfaceRaised" to 0xFFFFFFFF, "surfaceOverlay" to 0xFFEFF1F5,
        "outline" to 0xFFD8DCE5, "textPrimary" to 0xFF15171C, "textSecondary" to 0xFF525A6B,
        "textTertiary" to 0xFF6B7285, "accent" to 0xFF3A5BD9, "onAccent" to 0xFFFFFFFF,
        "debit" to 0xFFC2374A, "credit" to 0xFF1F7A5A, "warn" to 0xFF9A6410,
    )

    /** Each old name, read through its deprecated alias and through the role that replaces it. */
    private fun byOldName(c: LfColors): Map<String, Pair<Color, Color>> = mapOf(
        "surfaceBase" to (c.surfaceBase to c.surface.canvas),
        "surfaceRaised" to (c.surfaceRaised to c.surface.raised),
        "surfaceOverlay" to (c.surfaceOverlay to c.surface.overlay),
        "outline" to (c.outline to c.border.subtle),
        "textPrimary" to (c.textPrimary to c.text.primary),
        "textSecondary" to (c.textSecondary to c.text.secondary),
        "textTertiary" to (c.textTertiary to c.text.muted),
        "accent" to (c.accent to c.action.primary),
        "onAccent" to (c.onAccent to c.action.onPrimary),
        "debit" to (c.debit to c.money.outgoing),
        "credit" to (c.credit to c.money.incoming),
        "warn" to (c.warn to c.status.warning),
    )

    @Test
    fun everyDarkColourKeepsItsValue() = assertKeepsValues(LfDarkColors, darkBeforeB1)

    @Test
    fun everyLightColourKeepsItsValue() = assertKeepsValues(LfLightColors, lightBeforeB1)

    private fun assertKeepsValues(colors: LfColors, before: Map<String, Long>) {
        val now = byOldName(colors)
        assertThat(now.keys).containsExactlyElementsIn(before.keys)
        before.forEach { (name, argb) ->
            val (alias, role) = now.getValue(name)
            assertThat(alias).isEqualTo(Color(argb))
            assertThat(role).isEqualTo(Color(argb))
        }
    }

    /** Roles new in B1 reuse today's values: what the app already draws for that job. */
    @Test
    fun newRolesReuseTodaysValues() {
        listOf(LfDarkColors, LfLightColors).forEach { c ->
            assertThat(c.text.disabled).isEqualTo(c.text.muted) // LfButton's disabled label
            assertThat(c.border.strong).isEqualTo(c.text.muted) // A2's Material outline
            assertThat(c.status.danger).isEqualTo(c.money.outgoing) // errors have been coral
            assertThat(c.status.success).isEqualTo(c.money.incoming)
            assertThat(c.chart.track).isEqualTo(c.surface.overlay) // bar, heatmap and budget tracks
            assertThat(c.chart.axisLabel).isEqualTo(c.text.muted) // the stacked chart's labels
        }
    }

    @Test
    fun typeRolesAreTheSameStylesUnderNewNames() {
        LayoutDirection.entries.forEach { direction ->
            val scale = LfDefaultTypography.alignedTo(direction)
            val roles = scale.roles
            assertThat(roles.display).isEqualTo(scale.displayL)
            assertThat(roles.title).isEqualTo(scale.titleL)
            assertThat(roles.heading).isEqualTo(scale.titleM)
            assertThat(roles.body).isEqualTo(scale.bodyL)
            assertThat(roles.bodySmall).isEqualTo(scale.bodyM)
            assertThat(roles.label).isEqualTo(scale.label)
            assertThat(roles.amountHero).isEqualTo(scale.amountL)
            assertThat(roles.amountRow).isEqualTo(scale.amountM)
            assertThat(roles.mnemonic).isEqualTo(scale.mnemonicWord)
        }
        // BUG61: the roles follow the direction they were built for.
        assertThat(LfDefaultTypography.alignedTo(LayoutDirection.Rtl).roles.body.textAlign)
            .isNotEqualTo(LfDefaultTypography.alignedTo(LayoutDirection.Ltr).roles.body.textAlign)
    }

    @Test
    fun theTypeScaleKeepsItsSizes() {
        val t = LfDefaultTypography
        val styles = listOf(
            t.displayL, t.titleL, t.titleM, t.bodyL, t.bodyM, t.label, t.amountL, t.amountM, t.mnemonicWord,
        )
        assertThat(styles.map { it.fontSize.value to it.lineHeight.value })
            .containsExactly(
                34f to 40f, 22f to 28f, 18f to 24f, 16f to 24f, 14f to 20f,
                12f to 16f, 28f to 32f, 18f to 22f, 16f to 22f,
            )
            .inOrder()
    }

    @Test
    fun spacingAndMotionKeepTheirValues() {
        val s = LfSpacing()
        assertThat(listOf(s.xs, s.sm, s.md, s.lg, s.xl, s.xxl))
            .containsExactly(4.dp, 8.dp, 16.dp, 24.dp, 32.dp, 48.dp)
            .inOrder()
        assertThat(listOf(s.cornerSmall, s.cornerMedium, s.cornerLarge)).containsExactly(8.dp, 16.dp, 24.dp).inOrder()
        assertThat(listOf(s.minTouchTarget, s.actionMinWidth, s.segmentMinWidth, s.peekCardWidth))
            .containsExactly(48.dp, 112.dp, 72.dp, 172.dp)
            .inOrder()
        val m = LfMotion()
        assertThat(listOf(m.micro, m.standard, m.emphasized, m.fadeOut)).containsExactly(120, 240, 400, 60).inOrder()
        assertThat(m.axisSlide).isEqualTo(30.dp)
    }

    @Test
    fun spaceRolesAreStepsOfTheSameScale() {
        val r = LfDefaultSpaceRoles
        val s = LfSpacing()
        assertThat(r.screenEdge).isEqualTo(s.lg)
        assertThat(r.sectionGap).isEqualTo(s.md)
        assertThat(r.itemGap).isEqualTo(s.sm)
        assertThat(r.inlineGap).isEqualTo(s.xs)
        assertThat(r.cardInset).isEqualTo(s.md) // LfCard's padding
        assertThat(r.rowInset).isEqualTo(s.md) // LfEntryRow's start inset
        assertThat(r.rowInsetVertical).isEqualTo(s.sm)
        assertThat(r.blockGap).isEqualTo(s.lg) // LfPhraseEntry's gap between blocks
        assertThat(LfDefaultBorderRoles.hairline).isEqualTo(1.dp)
    }

    /** Step B3: each shape role is the radius its users had, so nothing on screen moved. */
    @Test
    fun shapeRolesKeepTodaysRadii() {
        val r = LfDefaultShapeRoles
        val s = LfSpacing()
        assertThat(r.container).isEqualTo(RoundedCornerShape(s.cornerMedium)) // LfCard
        assertThat(r.row).isEqualTo(RoundedCornerShape(s.cornerLarge)) // LfEntryRow
        assertThat(r.control).isEqualTo(RoundedCornerShape(s.cornerMedium)) // LfButton
        assertThat(r.dialog).isEqualTo(RoundedCornerShape(s.cornerLarge)) // LfDialog
        assertThat(r.full).isEqualTo(CircleShape) // LfFab
        assertThat(LfLegacySmallCorner).isEqualTo(RoundedCornerShape(s.cornerSmall)) // fields, chips
    }

    @Test
    fun layoutConstantsMovedUnchanged() {
        val s = LfSpacing()
        assertThat(LfLayout.minTouchTarget).isEqualTo(s.minTouchTarget)
        assertThat(LfLayout.actionMinWidth).isEqualTo(s.actionMinWidth)
        assertThat(LfLayout.segmentMinWidth).isEqualTo(s.segmentMinWidth)
        assertThat(LfLayout.peekCardWidth).isEqualTo(s.peekCardWidth)
        assertThat(LfLayout.categoryDotSize).isEqualTo(s.lg)
    }
}
