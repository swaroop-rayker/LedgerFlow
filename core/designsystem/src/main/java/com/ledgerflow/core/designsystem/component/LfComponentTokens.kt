package com.ledgerflow.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.theme.LfDarkColors
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfLegacySmallCorner
import com.ledgerflow.core.designsystem.theme.LfLightColors
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.designsystem.theme.primitive.LfScale

/*
 * Component tokens (DESIGN_SYSTEM_PLAN §4.1, step B2): what each building block
 * uses, named by the block's own parts.
 *
 * Colours and text come from the semantic roles (`LfTheme.colors`, `LfTheme.type`,
 * `LfTheme.space`, `LfTheme.borders`, `LfTheme.shapes`). A component's own
 * geometry -- a button's padding, a segment's inset -- comes from the primitive
 * scale, because it is a decision about that component rather than a meaning a
 * screen would ask for. Touch targets and other thresholds come from `LfLayout`
 * (step B3).
 *
 * All `internal`: a screen uses the component, never its tokens. A visual
 * direction that wants, say, borderless cards changes `LfCardTokens` and
 * nothing else.
 *
 * Step B2 moved every component onto these with today's values; every golden
 * is byte-identical before and after.
 */

internal object LfScaffoldTokens {
    val container: Color @Composable get() = LfTheme.colors.surface.canvas
    val content: Color @Composable get() = LfTheme.colors.text.primary
}

internal object LfButtonTokens {
    val minHeight: Dp = LfLayout.minTouchTarget
    val shape: Shape @Composable get() = LfTheme.shapes.control
    val labelStyle: TextStyle @Composable get() = LfTheme.type.body

    val filledContainer: Color @Composable get() = LfTheme.colors.action.primary
    val filledContent: Color @Composable get() = LfTheme.colors.action.onPrimary
    val tonalContainer: Color @Composable get() = LfTheme.colors.surface.overlay
    val tonalContent: Color @Composable get() = LfTheme.colors.text.primary

    val textLabel: Color @Composable get() = LfTheme.colors.action.primary
    val disabledLabel: Color @Composable get() = LfTheme.colors.text.disabled
    val inlineLabelStyle: TextStyle @Composable get() = LfTheme.type.label
    val inlinePaddingHorizontal: Dp = LfScale.Space8
    val inlinePaddingVertical: Dp = LfScale.Space4

    val spinnerSize: Dp = LfScale.Space16
    val spinnerStroke: Dp = LfScale.Stroke2
    val spinnerColor: Color @Composable get() = LfTheme.colors.action.onPrimary

    /** The row action ([LfButtonStyle.Outlined]). */
    val outlinedMinWidth: Dp = LfLayout.actionMinWidth
    val outlinedBorderWidth: Dp @Composable get() = LfTheme.borders.hairline
    val outlinedBorder: Color @Composable get() = LfTheme.colors.border.subtle
    val outlinedContainer: Color @Composable get() = LfTheme.colors.surface.overlay
    val outlinedContent: Color @Composable get() = LfTheme.colors.action.primary
    val outlinedPaddingHorizontal: Dp = LfScale.Space16
    val outlinedPaddingVertical: Dp = LfScale.Space8
}

internal object LfCardTokens {
    val container: Color @Composable get() = LfTheme.colors.surface.raised
    val border: Color @Composable get() = LfTheme.colors.border.subtle
    val borderWidth: Dp @Composable get() = LfTheme.borders.hairline
    val shape: Shape @Composable get() = LfTheme.shapes.container
    val inset: Dp @Composable get() = LfTheme.space.cardInset
}

internal object LfTextFieldTokens {
    val shape: Shape = LfLegacySmallCorner
    val textStyle: TextStyle @Composable get() = LfTheme.type.body
    val labelStyle: TextStyle @Composable get() = LfTheme.type.label
    val text: Color @Composable get() = LfTheme.colors.text.primary
    val focusedBorder: Color @Composable get() = LfTheme.colors.border.focus
    val unfocusedBorder: Color @Composable get() = LfTheme.colors.border.subtle
    val errorBorder: Color @Composable get() = LfTheme.colors.status.danger
    /** The error line under a field (step E1). */
    val errorText: Color @Composable get() = LfTheme.colors.status.danger
    val focusedLabel: Color @Composable get() = LfTheme.colors.action.primary
    val unfocusedLabel: Color @Composable get() = LfTheme.colors.text.secondary
    val cursor: Color @Composable get() = LfTheme.colors.action.primary
}

internal object LfChipTokens {
    val shape: Shape = LfLegacySmallCorner
    val minHeight: Dp = LfLayout.minTouchTarget
    val borderWidth: Dp @Composable get() = LfTheme.borders.hairline
    val paddingHorizontal: Dp = LfScale.Space16
    val paddingVertical: Dp = LfScale.Space8
    val gap: Dp = LfScale.Space8
    val textStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val supportingStyle: TextStyle @Composable get() = LfTheme.type.label
    val supporting: Color @Composable get() = LfTheme.colors.text.muted

    val assistContainer: Color @Composable get() = LfTheme.colors.surface.overlay
    val assistBorder: Color @Composable get() = LfTheme.colors.border.subtle
    val assistContent: Color @Composable get() = LfTheme.colors.text.primary
    val selectedContainer: Color @Composable get() = LfTheme.colors.surface.raised
    val selectedBorder: Color @Composable get() = LfTheme.colors.action.primary
    val selectedContent: Color @Composable get() = LfTheme.colors.text.primary
    val errorContainer: Color @Composable get() = LfTheme.colors.surface.raised
    val error: Color @Composable get() = LfTheme.colors.status.danger
    val warningContainer: Color @Composable get() = LfTheme.colors.surface.raised
    val warning: Color @Composable get() = LfTheme.colors.status.warning
}

internal object LfSegmentedTokens {
    val track: Color @Composable get() = LfTheme.colors.surface.raised
    val trackShape: Shape @Composable get() = LfTheme.shapes.control
    val trackPadding: Dp = LfScale.Space4
    val gap: Dp = LfScale.Space4
    val segmentShape: Shape = LfLegacySmallCorner
    val segmentMinHeight: Dp = LfLayout.minTouchTarget
    /** Below this per option the control stacks its options ([LfLayout.segmentMinWidth]). */
    val segmentMinWidth: Dp = LfLayout.segmentMinWidth
    val segmentPaddingHorizontal: Dp = LfScale.Space4
    val segmentPaddingVertical: Dp = LfScale.Space8
    val selectedContainer: Color @Composable get() = LfTheme.colors.action.primary
    val selectedContent: Color @Composable get() = LfTheme.colors.action.onPrimary
    val unselectedContainer: Color @Composable get() = LfTheme.colors.surface.raised
    val unselectedContent: Color @Composable get() = LfTheme.colors.text.secondary
    val textStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
}

internal object LfDialogTokens {
    val shape: Shape @Composable get() = LfTheme.shapes.dialog
    val container: Color @Composable get() = LfTheme.colors.surface.overlay
    val titleStyle: TextStyle @Composable get() = LfTheme.type.heading
    val title: Color @Composable get() = LfTheme.colors.text.primary
    val warningTitle: Color @Composable get() = LfTheme.colors.status.warning
    val bodyStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val body: Color @Composable get() = LfTheme.colors.text.secondary
    val bodyGap: Dp = LfScale.Space16
}

/** [LfDatePicker], [LfDatePickerDialog] and [LfDateRangePickerDialog]. */
internal object LfDatePickerTokens {
    val container: Color @Composable get() = LfTheme.colors.surface.overlay

    /**
     * The band behind the days between a range's two ends (BUG64). Material
     * draws it in `secondaryContainer`, which step A2 mapped to the dialog's
     * own colour, so the band vanished and only the two ends showed.
     */
    val rangeFill: Color @Composable get() = LfTheme.colors.action.subtle
    val rangeDay: Color @Composable get() = LfTheme.colors.text.primary
}

/** [LfBottomSheet]. */
internal object LfSheetTokens {
    val container: Color @Composable get() = LfTheme.colors.surface.raised
}

/** [LfSnackbarHost]: an inverse strip, the action in the other theme's action colour. */
internal object LfSnackbarTokens {
    val shape: Shape = LfLegacySmallCorner
    val container: Color @Composable get() = LfTheme.colors.surface.inverse
    val content: Color @Composable get() = LfTheme.colors.text.onInverse
    val action: Color
        @Composable get() = if (LfTheme.colors.isDark) LfLightColors.action.primary else LfDarkColors.action.primary
}

internal object LfIconButtonTokens {
    val tint: Color @Composable get() = LfTheme.colors.text.secondary
    val touchTarget: Dp = LfLayout.minTouchTarget
    /** The ring a pressed icon draws: the touch target less 4 dp on each side. */
    val stateLayerInset: Dp = LfScale.Space4
    val glyph: Dp = LfScale.Space16
}

internal object LfSwitchRowTokens {
    val prominentGap: Dp = LfScale.Space4
    val title: Color @Composable get() = LfTheme.colors.text.primary
    val supporting: Color @Composable get() = LfTheme.colors.text.secondary
    val prominentTitleStyle: TextStyle @Composable get() = LfTheme.type.body
    val titleStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val prominentSupportingStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val supportingStyle: TextStyle @Composable get() = LfTheme.type.label
}

/** [LfActionRow] and [LfAdaptiveRow]: the gap between whole controls. */
internal object LfActionRowTokens {
    val gap: Dp = LfScale.Space8
}

internal object LfNavTokens {
    val container: Color @Composable get() = LfTheme.colors.surface.raised
    val barPaddingVertical: Dp = LfScale.Space8
    val itemPaddingVertical: Dp = LfScale.Space4
    val itemGap: Dp = LfScale.Space4
    val labelStyle: TextStyle @Composable get() = LfTheme.type.label
    val selected: Color @Composable get() = LfTheme.colors.action.primary
    val unselected: Color @Composable get() = LfTheme.colors.text.muted
    val fabSize: Dp = LfLayout.minTouchTarget
    val fabShape: Shape @Composable get() = LfTheme.shapes.full
    val fabContainer: Color @Composable get() = LfTheme.colors.action.primary
    val fabContent: Color @Composable get() = LfTheme.colors.action.onPrimary
}

internal object LfScreenTitleTokens {
    val paddingHorizontal: Dp @Composable get() = LfTheme.space.screenEdge
    val paddingVertical: Dp = LfScale.Space16
    val gap: Dp = LfScale.Space4
    val titleStyle: TextStyle @Composable get() = LfTheme.type.display
    val title: Color @Composable get() = LfTheme.colors.text.primary
    val subtitleStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val subtitle: Color @Composable get() = LfTheme.colors.text.secondary
}

internal object LfEmptyStateTokens {
    val padding: Dp = LfScale.Space32
    val gap: Dp = LfScale.Space16
    val titleStyle: TextStyle @Composable get() = LfTheme.type.heading
    val title: Color @Composable get() = LfTheme.colors.text.primary
    val bodyStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val body: Color @Composable get() = LfTheme.colors.text.secondary
}

/**
 * [LfNotice] and [LfErrorNotice] (step E1): the tone's faint fill, its colour as
 * the edge, and primary text on both, which `NoticeContrastTest` holds at 4.5:1.
 */
internal object LfNoticeTokens {
    val shape: Shape @Composable get() = LfTheme.shapes.container
    val edgeWidth: Dp @Composable get() = LfTheme.borders.hairline
    val insetHorizontal: Dp @Composable get() = LfTheme.space.cardInset
    val insetVertical: Dp @Composable get() = LfTheme.space.rowInsetVertical
    val gap: Dp @Composable get() = LfTheme.space.inlineGap
    val titleStyle: TextStyle @Composable get() = LfTheme.type.body
    val bodyStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val text: Color @Composable get() = LfTheme.colors.text.primary

    val infoEdge: Color @Composable get() = LfTheme.colors.status.info
    val infoFill: Color @Composable get() = LfTheme.colors.status.infoSubtle
    val successEdge: Color @Composable get() = LfTheme.colors.status.success
    val successFill: Color @Composable get() = LfTheme.colors.status.successSubtle
    val warningEdge: Color @Composable get() = LfTheme.colors.status.warning
    val warningFill: Color @Composable get() = LfTheme.colors.status.warningSubtle
    val errorEdge: Color @Composable get() = LfTheme.colors.status.danger
    val errorFill: Color @Composable get() = LfTheme.colors.status.dangerSubtle
}

/** [LfProgressBar] (step E1): the budget bar's track, height and ink. */
internal object LfProgressBarTokens {
    val height: Dp = LfScale.Space8
    val track: Color @Composable get() = LfTheme.colors.chart.track
    val accent: Color @Composable get() = LfTheme.colors.action.primary
    val positive: Color @Composable get() = LfTheme.colors.status.success
    val warning: Color @Composable get() = LfTheme.colors.status.warning
}

internal object LfDividerTokens {
    val thickness: Dp @Composable get() = LfTheme.borders.hairline
    val color: Color @Composable get() = LfTheme.colors.border.subtle
}

internal object LfCategoryDotTokens {
    val size: Dp = LfLayout.categoryDotSize
}

internal object LfAmountFieldTokens {
    val neutral: Color @Composable get() = LfTheme.colors.text.primary
    val outgoing: Color @Composable get() = LfTheme.colors.money.outgoing
    val incoming: Color @Composable get() = LfTheme.colors.money.incoming
    val gap: Dp = LfScale.Space4
    val labelStyle: TextStyle @Composable get() = LfTheme.type.label
    val label: Color @Composable get() = LfTheme.colors.text.secondary
    val hintStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val hint: Color @Composable get() = LfTheme.colors.text.muted
    val selection: Color @Composable get() = LfTheme.colors.action.primary
    val cursor: Color @Composable get() = LfTheme.colors.action.primary
    val amountStyle: TextStyle @Composable get() = LfTheme.type.amountHero
    val symbolStyle: TextStyle @Composable get() = LfTheme.type.title
    val symbol: Color @Composable get() = LfTheme.colors.text.secondary
    val placeholder: Color @Composable get() = LfTheme.colors.text.muted
}
