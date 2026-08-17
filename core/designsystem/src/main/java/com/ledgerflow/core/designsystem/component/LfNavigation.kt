package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The bottom bar: four destinations with a raised action in the middle
 * (SPEC.md §9.3).
 *
 * The centre action is laid out **as the third of five cells** rather than
 * docked over a `NavigationBar` via `FabPosition`. A docked FAB overlaps the
 * bar, and at 2.0x font scale the labels grow underneath it until the two
 * collide -- BUG5 in miniature. Giving it its own cell means the row simply
 * gets taller and nothing overlaps.
 *
 * Insets are not handled here: `LfScaffold` consumes `safeDrawing` for whatever
 * it is given as a bottom bar, so this stays a pure row.
 */
@Composable
public fun LfBottomBar(
    items: List<LfNavItem>,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    addContentDescription: String = "Add an entry",
) {
    require(items.size == NAV_ITEM_COUNT) {
        "The bottom bar takes exactly $NAV_ITEM_COUNT destinations, got ${items.size}"
    }
    val colors = LfTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(vertical = LfTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarItem(items[0], Modifier.weight(1f))
        BarItem(items[1], Modifier.weight(1f))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LfFab(onClick = onAddClick, contentDescription = addContentDescription)
        }
        BarItem(items[2], Modifier.weight(1f))
        BarItem(items[3], Modifier.weight(1f))
    }
}

@Composable
private fun BarItem(item: LfNavItem, modifier: Modifier = Modifier) {
    val colors = LfTheme.colors
    val tint = if (item.selected) colors.accent else colors.textTertiary

    Column(
        modifier = modifier
            .selectable(
                selected = item.selected,
                role = Role.Tab,
                onClick = item.onClick,
            )
            .padding(vertical = LfTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        // The label already names the destination and the row owns the selection
        // state, so the icon must not announce itself or TalkBack says it twice.
        Icon(imageVector = item.icon, contentDescription = null, tint = tint)
        Text(
            text = item.label,
            style = LfTheme.typography.label,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/** The centre action. Circular, accent-filled, always at least 48dp. */
@Composable
public fun LfFab(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = LfIcons.Add,
) {
    val colors = LfTheme.colors
    Box(
        modifier = modifier
            .size(LfTheme.spacing.minTouchTarget)
            .background(colors.accent, CircleShape)
            .selectable(selected = false, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.onAccent,
            modifier = Modifier.size(FAB_ICON_SIZE.dp),
        )
    }
}

/**
 * One bottom-bar destination.
 *
 * Declared after the composables to match the other atom files: detekt's
 * MatchingDeclarationName fires when a file's *first* top-level declaration is
 * a class whose name is not the filename.
 */
@Immutable
public data class LfNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

private const val NAV_ITEM_COUNT = 4
private const val FAB_ICON_SIZE = 24
