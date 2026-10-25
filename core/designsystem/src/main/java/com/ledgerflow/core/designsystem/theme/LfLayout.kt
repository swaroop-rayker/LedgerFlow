package com.ledgerflow.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.primitive.LfScale

/**
 * Layout constants (DESIGN_SYSTEM_PLAN §4.2, step B3).
 *
 * Not tokens: a visual direction does not change these. Each one is a measured
 * threshold or a guarantee, and moving it changes behaviour (when a control
 * stacks, whether a row of cards still reads as scrollable) rather than looks.
 * A component's own size, which a direction *does* change, lives in its
 * component tokens.
 */
public object LfLayout {
    /** Minimum touch target (§9.6). Not negotiable. */
    public val minTouchTarget: Dp = LfScale.Space48

    /**
     * Minimum width for a row action.
     *
     * A short label like "Delete" or "Hide" otherwise renders as a tight pill,
     * and looks especially unintended when it is the one control that wrapped
     * onto its own line beneath two wider ones. This is a *minimum*: a button
     * whose label needs more keeps its natural width, so widening can never
     * clip anything (BUG9).
     *
     * **112, not 128.** A card's inner width on a 360dp phone is ~261dp, so two
     * actions plus their 8dp gap have ~126dp each. 128 missed that by two
     * device pixels and silently stacked all three actions one per line, which
     * made the card twice as tall — a minimum width chosen at the boundary is a
     * layout that flips on a rounding error. This leaves real headroom.
     */
    public val actionMinWidth: Dp = 112.dp

    /**
     * Width one segment of `LfSegmentedControl` needs at font scale 1.0.
     *
     * Below this the control stacks its options vertically instead of dividing
     * the row. A segment cannot grow to fit its label the way a button can --
     * the cells split a fixed width -- so without the switch the label has no
     * option but to break mid-word, which is BUG9.
     *
     * Measured, not guessed: on a 360dp phone a screen-padded control gives
     * ~101dp per option across three, and "Categories" needs ~78dp of that at
     * font scale 1.15. 96 tripped at 1.15 and stacked a row that fitted
     * perfectly well; 72 keeps it horizontal there and still stacks at 2.0,
     * where the same option would need ~144dp.
     */
    public val segmentMinWidth: Dp = 72.dp

    /**
     * Width of one card in a horizontally-scrolling peek row.
     *
     * Sized so a second card is always partly visible on a 360dp screen. That
     * sliver is the entire affordance -- a row of cards that happens to fit
     * exactly reads as a complete list, and nobody swipes it.
     */
    public val peekCardWidth: Dp = 172.dp

    /**
     * A category swatch (`LfCategoryDot`, and the entry row's swatch slot).
     * Its initial is half this, pinned to the shape rather than the font scale.
     */
    public val categoryDotSize: Dp = LfScale.Space24
}
