package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

/**
 * A row-shaped placeholder (DESIGN_SYSTEM_PLAN §5.1, step E1): the row's own
 * card and edge, with a bar for each line it will hold.
 *
 * Still, not shimmering (owner, 2026-10-27). A placeholder that moves draws
 * every frame for as long as the load takes, and has to be switched off for
 * Animations Off; one that holds still costs a single frame and says the same
 * thing. Decoration to a screen reader: [LfSkeletonList] speaks for the set.
 */
@Composable
public fun LfSkeletonRow(modifier: Modifier = Modifier, lines: Int = 2) {
    val tokens = LfSkeletonTokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .background(tokens.container, tokens.rowShape)
            .border(tokens.edgeWidth, tokens.edge, tokens.rowShape)
            .padding(horizontal = tokens.insetHorizontal, vertical = tokens.insetVertical),
        verticalArrangement = Arrangement.spacedBy(tokens.lineGap),
    ) {
        repeat(lines.coerceAtLeast(1)) { line ->
            Box(
                Modifier
                    // The first line is the title: longer and taller, as the text will be.
                    .fillMaxWidth(if (line == 0) TITLE_WIDTH else DETAIL_WIDTH)
                    .height(if (line == 0) tokens.titleHeight else tokens.detailHeight)
                    .background(tokens.bar, tokens.barShape),
            )
        }
    }
}

/** [rows] skeleton rows, heard once as "Loading". */
@Composable
public fun LfSkeletonList(modifier: Modifier = Modifier, rows: Int = 3, lines: Int = 2) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(LfSkeletonTokens.rowGap),
    ) {
        repeat(rows) { LfSkeletonRow(lines = lines) }
    }
}

private const val TITLE_WIDTH = 0.6f
private const val DETAIL_WIDTH = 0.35f
