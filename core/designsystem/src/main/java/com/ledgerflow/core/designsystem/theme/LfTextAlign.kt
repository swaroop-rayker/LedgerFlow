package com.ledgerflow.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/**
 * Text alignment measured from the **screen**, not from the text (BUG61).
 *
 * Compose's `TextAlign.Start` and `End` follow the paragraph's own direction.
 * Since BUG61 a paragraph's direction is its text's (an English sentence is LTR
 * even on a mirrored screen, so its full stop stays at the end), which makes
 * `End` on an English amount mean "right" in Arabic too -- where the screen's end
 * is the left. These resolve against the layout direction instead, the way a
 * `TextView`'s `gravity="start"` does, so a column of amounts still lines up on
 * the side the screen reads towards.
 *
 * `Bug61_EnglishTextKeepsItsPunctuationInRtlTest` keeps `TextAlign.Start`/`End`
 * out of main sources; this file is the one place allowed to name them.
 * `Center` is direction-free and stays as it is.
 */
public object LfTextAlign {

    /** The screen's leading edge: left in LTR, right in RTL. */
    public val start: TextAlign
        @Composable get() = startOf(LocalLayoutDirection.current)

    /** The screen's trailing edge: right in LTR, left in RTL. */
    public val end: TextAlign
        @Composable get() = endOf(LocalLayoutDirection.current)

    /** [start] for a known [direction], for code that is not composable. */
    public fun startOf(direction: LayoutDirection): TextAlign = when (direction) {
        LayoutDirection.Ltr -> TextAlign.Left
        LayoutDirection.Rtl -> TextAlign.Right
    }

    /** [end] for a known [direction]. */
    public fun endOf(direction: LayoutDirection): TextAlign = when (direction) {
        LayoutDirection.Ltr -> TextAlign.Right
        LayoutDirection.Rtl -> TextAlign.Left
    }
}
