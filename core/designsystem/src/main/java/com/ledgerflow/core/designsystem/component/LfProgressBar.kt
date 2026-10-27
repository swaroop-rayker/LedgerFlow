package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * A bar filled to [fraction] of its track (DESIGN_SYSTEM_PLAN §3, step E1):
 * `LfBudgetBar`'s bar, generalised, for a backup or restore under way and any
 * "this much of that".
 *
 * Full-round ends, 8 dp tall. [fraction] is clamped to 0..1; a value past 1 is
 * the caller's to say in words, as the budget bar does. A screen reader hears
 * it as a progress bar with its percentage, or [contentDescription] when given.
 */
@Composable
public fun LfProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: LfProgressTone = LfProgressTone.Accent,
    contentDescription: String? = null,
) {
    val tokens = LfProgressBarTokens
    val clamped = fraction.coerceIn(0f, 1f)
    ProgressTrack(
        fraction = clamped,
        fill = when (tone) {
            LfProgressTone.Accent -> tokens.accent
            LfProgressTone.Positive -> tokens.positive
            LfProgressTone.Warning -> tokens.warning
        },
        track = tokens.track,
        height = tokens.height,
        modifier = modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            contentDescription?.let { this.contentDescription = it }
        },
    )
}

/**
 * The track and its fill, for [LfProgressBar] and `LfBudgetBar` (which adds
 * its projection tick in [overlay] and says its own words).
 */
@Composable
internal fun ProgressTrack(
    fraction: Float,
    fill: Color,
    track: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        FractionalFill(fraction = fraction.coerceIn(0f, 1f), color = fill)
        overlay()
    }
}

@Composable
private fun FractionalFill(fraction: Float, color: Color) {
    Layout(
        content = {},
        modifier = Modifier
            .background(color)
            .clearAndSetSemantics { },
    ) { _, constraints ->
        val width = (constraints.maxWidth * fraction).toInt().coerceIn(0, constraints.maxWidth)
        layout(width, constraints.maxHeight) {}
    }
}

/**
 * What a progress bar's fill means; each is a colour role. Declared after the
 * composables (detekt's MatchingDeclarationName, as in `LfChip.kt`).
 */
public enum class LfProgressTone {
    /** Work under way, or a share of something: the accent. */
    Accent,

    /** Done, or comfortably inside a limit. */
    Positive,

    /** Past a limit. A fact, not an alarm: the warning colour, never danger. */
    Warning,
}
