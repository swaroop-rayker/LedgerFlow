package com.ledgerflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle

/**
 * Text styles by job (DESIGN_SYSTEM_PLAN §4.2), read as `LfTheme.type.body`.
 *
 * The same styles as the type scale they replace, under names that say what a
 * piece of text is rather than how big it is, so a visual direction can resize
 * a role without a screen noticing. Every amount style keeps tabular figures
 * (SPEC §9.2), [mnemonic] keeps Inter's disambiguation set, and every style is
 * aligned to the screen's leading edge for its layout direction (BUG61):
 * `LfTheme.type` hands out the copy built for the current direction.
 *
 * Where a role has no distinct style yet it shares one ([caption] is [label],
 * [amountSmall] is [amountRow]), so a screen can name the meaning now and a
 * direction can separate them later without touching that screen.
 */
@Immutable
public class LfTypeRoles internal constructor(
    /** The largest heading: a first-run step's title. */
    public val display: TextStyle,
    /** A screen's title. */
    public val title: TextStyle,
    /** A section or card heading. */
    public val heading: TextStyle,
    public val body: TextStyle,
    public val bodySmall: TextStyle,
    /** Labels, row metadata, button text in rows. */
    public val label: TextStyle,
    /** The smallest text: footnotes and stamps. Today the same as [label]. */
    public val caption: TextStyle,
    /** A screen's headline figure. */
    public val amountHero: TextStyle,
    /** An amount in a row or card. */
    public val amountRow: TextStyle,
    /** An amount in a supporting line. Today the same as [amountRow]. */
    public val amountSmall: TextStyle,
    /** A recovery word: disambiguated glyphs, so a word copied off the screen is copied right. */
    public val mnemonic: TextStyle,
)

/** The roles over a type scale; [LfTypography] caches one per direction. */
internal fun LfTypography.toRoles(): LfTypeRoles = LfTypeRoles(
    display = displayL,
    title = titleL,
    heading = titleM,
    body = bodyL,
    bodySmall = bodyM,
    label = label,
    caption = label,
    amountHero = amountL,
    amountRow = amountM,
    amountSmall = amountM,
    mnemonic = mnemonicWord,
)
