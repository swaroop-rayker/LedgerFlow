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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme

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
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, RoundedCornerShape(spacing.cornerMedium))
            .padding(spacing.xs),
    ) {
        // Unlike a button, a segment cannot grow to fit its label -- the cells
        // divide a fixed width. So at large font scales the choice is between
        // breaking words and changing the layout, and BUG9 already settled that:
        // "Categories" rendered as "Catego / ries" at 2.0x before this.
        //
        // The threshold scales with the font, because that is what makes the
        // labels wider in the first place. §9.6 requires 2.0x without truncation
        // or overlap, and three options on a 360dp phone cannot have it any
        // other way.
        val perOption = maxWidth / options.size
        val needed = spacing.segmentMinWidth * LocalDensity.current.fontScale

        if (perOption >= needed) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                options.forEachIndexed { index, label ->
                    Segment(label, index == selectedIndex, { onSelect(index) }, Modifier.weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                options.forEachIndexed { index, label ->
                    Segment(
                        label,
                        index == selectedIndex,
                        { onSelect(index) },
                        Modifier.fillMaxWidth(),
                    )
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
    modifier: Modifier = Modifier,
) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing
    Box(
        modifier = modifier
            .background(
                color = if (selected) colors.accent else colors.surfaceRaised,
                shape = RoundedCornerShape(spacing.cornerSmall),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = spacing.minTouchTarget)
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = if (selected) colors.onAccent else colors.textSecondary,
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
