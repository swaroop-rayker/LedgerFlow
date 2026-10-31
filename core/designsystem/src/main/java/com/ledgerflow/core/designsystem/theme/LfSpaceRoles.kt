package com.ledgerflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.theme.primitive.LfScale

/**
 * Space by job (DESIGN_SYSTEM_PLAN §4.2), read as `LfTheme.space.screenEdge`.
 *
 * Today's values: each role is the step the app most commonly uses for that
 * job. They are not all applied yet. The audit found the screen edge at 16 dp
 * on some screens and 24 dp on others, and the screens move onto these roles
 * in step D, one reviewed batch at a time. That is when the edges become one
 * value; step B1 only gives the value a name.
 */
@Immutable
public class LfSpaceRoles internal constructor(
    /** Between the screen's edge and its content. */
    public val screenEdge: Dp,
    /** Between the sections of a screen (Home's cards, Settings' groups). */
    public val sectionGap: Dp,
    /** Between the blocks of a one-task step (the phrase entry's instructions, words and actions). */
    public val blockGap: Dp,
    /** Between items inside a section. */
    public val itemGap: Dp,
    /** Between pieces on one line: an icon and its label, a chip and the next. */
    public val inlineGap: Dp,
    /** Inside a card, on every side. */
    public val cardInset: Dp,
    /** Inside a list row, at its leading and trailing edges. */
    public val rowInset: Dp,
    /** Inside a list row, above and below its content. */
    public val rowInsetVertical: Dp,
    /** After a list's last item, so it clears the bar below it. */
    public val listEnd: Dp,
    /** Above and below a pinned action bar's button. Small: `LfScaffold` has already inset the bar for the navigation bar. */
    public val actionBarInset: Dp,
)

internal val LfDefaultSpaceRoles: LfSpaceRoles = LfSpaceRoles(
    screenEdge = LfScale.Space24,
    sectionGap = LfScale.Space16,
    blockGap = LfScale.Space24,
    itemGap = LfScale.Space8,
    inlineGap = LfScale.Space4,
    cardInset = LfScale.Space16,
    rowInset = LfScale.Space16,
    rowInsetVertical = LfScale.Space8,
    listEnd = LfScale.Space32,
    actionBarInset = LfScale.Space4,
)

public val LocalLfSpaceRoles: androidx.compose.runtime.ProvidableCompositionLocal<LfSpaceRoles> =
    staticCompositionLocalOf { LfDefaultSpaceRoles }

/**
 * Stroke widths by job, read as `LfTheme.borders.hairline`. The colours are in
 * `LfTheme.colors.border`; this is how thick.
 */
@Immutable
public class LfBorderRoles internal constructor(
    /** Every list-sized container's edge (`CLAUDE.md` §5: hairline over elevation). */
    public val hairline: Dp,
    /** An edge that marks a state, like the draft being edited. */
    public val emphasis: Dp,
)

internal val LfDefaultBorderRoles: LfBorderRoles = LfBorderRoles(
    hairline = LfScale.Stroke1,
    emphasis = LfScale.Stroke1,
)

public val LocalLfBorderRoles: androidx.compose.runtime.ProvidableCompositionLocal<LfBorderRoles> =
    staticCompositionLocalOf { LfDefaultBorderRoles }
