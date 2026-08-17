package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, RoundedCornerShape(spacing.cornerMedium))
            .padding(spacing.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) colors.accent else colors.surfaceRaised,
                        shape = RoundedCornerShape(spacing.cornerSmall),
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .defaultMinSize(minHeight = spacing.minTouchTarget)
                    .padding(vertical = spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = LfTheme.typography.bodyM,
                    color = if (selected) colors.onAccent else colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val MIN_OPTIONS = 2
