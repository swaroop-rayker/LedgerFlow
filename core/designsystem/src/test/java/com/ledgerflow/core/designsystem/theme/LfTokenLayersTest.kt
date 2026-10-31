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
 * The hex values below are Night Vault's (ADR-0031, step F2), written out on
 * purpose: if a role is ever re-pointed at a different primitive by mistake,
 * this fails, where a test comparing the roles with the aliases would not (both
 * would move together). They change only with a deliberate palette change, and
 * then this test changes in the same commit. (Until F2 they were the values
 * `LfColors.kt` held before B1.)
 */
@Suppress("DEPRECATION") // The aliases are what this test pins to the roles.
class LfTokenLayersTest {

    private val darkBeforeB1 = mapOf(
        "surfaceBase" to 0xFF121316, "surfaceRaised" to 0xFF1A1B1F, "surfaceOverlay" to 0xFF222429,
        "outline" to 0xFF2B2D33, "textPrimary" to 0xFFECE8E0, "textSecondary" to 0xFFA39F96,
        "textTertiary" to 0xFF908C84, "accent" to 0xFFECE8E0, "onAccent" to 0xFF121316,
        "debit" to 0xFFFF8A80, "credit" to 0xFF6FD6A8, "warn" to 0xFFF2B457,
    )

    private val lightBeforeB1 = mapOf(
        "surfaceBase" to 0xFFF4F3F0, "surfaceRaised" to 0xFFFFFFFF, "surfaceOverlay" to 0xFFEEECE8,
        "outline" to 0xFFE3E0D9, "textPrimary" to 0xFF17181B, "textSecondary" to 0xFF55534D,
        "textTertiary" to 0xFF6A675F, "accent" to 0xFF17181B, "onAccent" to 0xFFFFFFFF,
        "debit" to 0xFFB3362F, "credit" to 0xFF1D7350, "warn" to 0xFF8F5B00,
    )

    /** The two roles F2 gave values of their own (ADR-0031). */
    @Test
    fun nightVaultsOwnRolesKeepTheirValues() {
        assertThat(LfDarkColors.border.strong).isEqualTo(Color(0xFF75716A))
        assertThat(LfLightColors.border.strong).isEqualTo(Color(0xFF837F77))
        assertThat(LfDarkColors.status.danger).isEqualTo(Color(0xFFF49AC1))
        assertThat(LfLightColors.status.danger).isEqualTo(Color(0xFFA8336F))
    }

    /**
     * Each colour by its pre-B1 name, read through the role that replaced it.
     * Step G removed the aliases themselves; the values they pinned stay pinned.
     */
    private fun byOldName(c: LfColors): Map<String, Color> = mapOf(
        "surfaceBase" to c.surface.canvas,
        "surfaceRaised" to c.surface.raised,
        "surfaceOverlay" to c.surface.overlay,
        "outline" to c.border.subtle,
        "textPrimary" to c.text.primary,
        "textSecondary" to c.text.secondary,
        "textTertiary" to c.text.muted,
        "accent" to c.action.primary,
        "onAccent" to c.action.onPrimary,
        "debit" to c.money.outgoing,
        "credit" to c.money.incoming,
        "warn" to c.status.warning,
    )

    @Test
    fun everyDarkColourKeepsItsValue() = assertKeepsValues(LfDarkColors, darkBeforeB1)

    @Test
    fun everyLightColourKeepsItsValue() = assertKeepsValues(LfLightColors, lightBeforeB1)

    private fun assertKeepsValues(colors: LfColors, before: Map<String, Long>) {
        val now = byOldName(colors)
        assertThat(now.keys).containsExactlyElementsIn(before.keys)
        before.forEach { (name, argb) -> assertThat(now.getValue(name)).isEqualTo(Color(argb)) }
    }

    /**
     * Roles new in B1 reuse an existing value: what the app already drew for that
     * job. Two have their own since F2, and those are pinned above; danger is
     * now *required* to differ from money-out (ADR-0031).
     */
    @Test
    fun newRolesReuseTodaysValues() {
        listOf(LfDarkColors, LfLightColors).forEach { c ->
            assertThat(c.text.disabled).isEqualTo(c.text.muted) // LfButton's disabled label
            assertThat(c.status.danger).isNotEqualTo(c.money.outgoing) // a problem is not an amount
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
    fun motionKeepsItsValues() {
        val m = LfMotion()
        assertThat(listOf(m.micro, m.standard, m.emphasized, m.fadeOut)).containsExactly(120, 240, 400, 60).inOrder()
        assertThat(m.axisSlide).isEqualTo(30.dp)
    }

    /** The roles are steps of the one scale; step G removed the old names for the steps. */
    @Test
    fun spaceRolesAreStepsOfTheSameScale() {
        val r = LfDefaultSpaceRoles
        assertThat(r.screenEdge).isEqualTo(24.dp)
        assertThat(r.sectionGap).isEqualTo(16.dp)
        assertThat(r.itemGap).isEqualTo(8.dp)
        assertThat(r.inlineGap).isEqualTo(4.dp)
        assertThat(r.cardInset).isEqualTo(16.dp) // LfCard's padding
        assertThat(r.rowInset).isEqualTo(16.dp) // LfEntryRow's start inset
        assertThat(r.rowInsetVertical).isEqualTo(8.dp)
        assertThat(r.blockGap).isEqualTo(24.dp) // LfPhraseEntry's gap between blocks
        assertThat(r.listEnd).isEqualTo(32.dp)
        assertThat(LfDefaultBorderRoles.hairline).isEqualTo(1.dp)
    }

    /** Night Vault's radii (F3, ADR-0031): 16 for containers, rows and controls, 24 for dialogs, 12 for tiles. */
    @Test
    fun shapeRolesKeepTheirRadii() {
        val r = LfDefaultShapeRoles
        assertThat(r.container).isEqualTo(RoundedCornerShape(16.dp)) // LfCard
        assertThat(r.row).isEqualTo(RoundedCornerShape(16.dp)) // every standalone row, D3
        assertThat(r.control).isEqualTo(RoundedCornerShape(16.dp)) // LfButton
        assertThat(r.dialog).isEqualTo(RoundedCornerShape(24.dp)) // LfDialog
        assertThat(r.tile).isEqualTo(RoundedCornerShape(12.dp)) // category tiles, chart cells (F6)
        assertThat(r.full).isEqualTo(CircleShape) // LfFab
    }

    @Test
    fun layoutConstantsKeepTheirValues() {
        assertThat(LfLayout.minTouchTarget).isEqualTo(48.dp)
        assertThat(LfLayout.actionMinWidth).isEqualTo(112.dp)
        assertThat(LfLayout.segmentMinWidth).isEqualTo(72.dp)
        assertThat(LfLayout.peekCardWidth).isEqualTo(172.dp)
        assertThat(LfLayout.categoryDotSize).isEqualTo(24.dp)
        assertThat(LfLayout.dialogListMax).isEqualTo(320.dp) // D3: one of five values
    }
}
