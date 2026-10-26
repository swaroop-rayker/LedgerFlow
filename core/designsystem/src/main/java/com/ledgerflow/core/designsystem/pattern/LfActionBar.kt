package com.ledgerflow.core.designsystem.pattern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The bar pinned under a screen's content: its main action, or a few
 * (DESIGN_SYSTEM_PLAN §4.4, step D1).
 *
 * The screen edge across, so the button lines up with everything above it, and
 * only `actionBarInset` above and below. `LfScaffold` has already inset the bar
 * for the navigation bar, so a large inset here would stack on top of one that
 * exists for the same reason, and the height would come out of the one part of
 * the screen that scrolls (`CLAUDE.md` §5). Before D1 every screen padded its
 * own bar, in five different ways.
 */
@Composable
public fun LfActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = LfTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = space.screenEdge, vertical = space.actionBarInset),
        verticalArrangement = Arrangement.spacedBy(space.itemGap),
        content = content,
    )
}

/**
 * What replaces a page's action bar while rows are selected: how many, and
 * what can be done to them (DESIGN_SYSTEM_PLAN §5.6: Merchants, Receipts, the
 * Inbox and the bin all select). The actions wrap as whole controls (BUG9).
 *
 * @param label "3 selected", in the screen's own words.
 */
@Composable
public fun LfSelectionBar(
    label: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    LfActionBar(modifier = modifier) {
        Text(text = label, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
        LfActionRow(alignment = LfActionAlignment.End) { actions() }
    }
}
