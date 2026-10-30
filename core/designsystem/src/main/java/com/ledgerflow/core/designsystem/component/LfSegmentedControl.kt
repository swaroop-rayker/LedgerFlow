package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

/**
 * A small set of mutually exclusive options (SPEC.md §9.4).
 *
 * Its first job is the `Expenses | Income` control, and that is not a filter —
 * it selects which of two disjoint books you are looking at (Law 2). The
 * component is written accordingly: exactly one option is selected at all
 * times, and there is no "all" state to fall into.
 *
 * `selectableGroup()` matters for TalkBack: without it each option announces
 * itself as an independent control rather than as "1 of 2".
 */
@Composable
public fun LfSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.size >= MIN_OPTIONS) { "A segmented control needs at least $MIN_OPTIONS options" }
    val tokens = LfSegmentedTokens

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Unlike a button, a segment cannot grow to fit its label -- the cells
        // divide a fixed width. So at large font scales the choice is between
        // breaking words and changing the layout, and BUG9 already settled that:
        // "Categories" rendered as "Catego / ries" at 2.0x before this.
        //
        // The threshold scales with the font, because that is what makes the
        // labels wider in the first place. §9.6 requires 2.0x without truncation
        // or overlap, and three options on a 360dp phone cannot have it any
        // other way.
        val perOption = (maxWidth - tokens.trackPadding * 2 - tokens.gap * (options.size - 1)) / options.size
        val needed = tokens.segmentMinWidth * LocalDensity.current.fontScale
        // BUG80: the threshold alone let Lock after's four options sit on one
        // row at 1.0 with "Immediately" and "15 minutes" cut off. The widest
        // label, as it will be drawn, decides as well.
        val measurer = rememberTextMeasurer()
        val style = tokens.textStyle
        val widestLabel = with(LocalDensity.current) {
            options.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }.toDp()
        }
        val stacked = perOption < needed || perOption < widestLabel + tokens.segmentPaddingHorizontal * 2

        // **A stacked track is a card, not a pill** (BUG79, owner 2026-10-30). The
        // pill's fully round corners on a track four options tall drew a
        // capsule with ends half its width across -- Lock after's and Date
        // order's dialogs. Stacked, the track takes the control's 16 dp and
        // each option 12, so the corners nest across the 4 dp padding; a
        // single row stays pill inside pill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.track, if (stacked) tokens.stackedTrackShape else tokens.trackShape)
                .padding(tokens.trackPadding),
        ) {
            if (!stacked) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                ) {
                    options.forEachIndexed { index, label ->
                        Segment(
                            label,
                            index == selectedIndex,
                            { onSelect(index) },
                            tokens.segmentShape,
                            Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(tokens.gap),
                ) {
                    options.forEachIndexed { index, label ->
                        Segment(
                            label,
                            index == selectedIndex,
                            { onSelect(index) },
                            tokens.stackedSegmentShape,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val tokens = LfSegmentedTokens
    Box(
        modifier = modifier
            .background(
                color = if (selected) tokens.selectedContainer else tokens.unselectedContainer,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = tokens.segmentMinHeight)
            .padding(horizontal = tokens.segmentPaddingHorizontal, vertical = tokens.segmentPaddingVertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = tokens.textStyle,
            color = if (selected) tokens.selectedContent else tokens.unselectedContent,
            textAlign = TextAlign.Center,
            // The same contract every control label carries (BUG9): one line,
            // never broken mid-word. The layout above is what guarantees there
            // is room for it.
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val MIN_OPTIONS = 2
