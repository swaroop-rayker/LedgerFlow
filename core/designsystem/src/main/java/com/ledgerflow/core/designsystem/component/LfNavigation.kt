package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.icon.LfIcons

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
 * **The + sits half above the bar** (the reference samples, step F3): its
 * centre on the bar's top edge, ringed in the page colour so it reads as cut
 * out of the content behind it. Its cell stays empty and keeps its width, so
 * the four labels still have the room above. The + is placed by the bar's box
 * rather than by its cell, so its 56 dp does not make the bar taller; touches
 * on the raised half still reach it, because nothing above clips the bar
 * (`LfBottomBarRaisedAddTest`).
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
    val edge = LfNavTokens.edge
    val edgeWidth = LfNavTokens.edgeWidth
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LfNavTokens.container)
            .drawBehind {
                drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), edgeWidth.toPx())
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LfNavTokens.barPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarItem(items[0], Modifier.weight(1f))
            BarItem(items[1], Modifier.weight(1f))
            // The +'s cell: its width, so the labels keep their room.
            Spacer(modifier = Modifier.weight(1f))
            BarItem(items[2], Modifier.weight(1f))
            BarItem(items[3], Modifier.weight(1f))
        }
        LfFab(
            onClick = onAddClick,
            contentDescription = addContentDescription,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Centred on the bar's top edge: half the circle, plus its ring.
                .offset(y = -(LfNavTokens.fabSize / 2 + LfNavTokens.fabRingWidth))
                .border(LfNavTokens.fabRingWidth, LfNavTokens.fabRing, LfNavTokens.fabShape)
                .padding(LfNavTokens.fabRingWidth),
        )
    }
}

@Composable
private fun BarItem(item: LfNavItem, modifier: Modifier = Modifier) {
    val tint = if (item.selected) LfNavTokens.selected else LfNavTokens.unselected

    Column(
        modifier = modifier
            .selectable(
                selected = item.selected,
                role = Role.Tab,
                onClick = item.onClick,
            )
            .padding(vertical = LfNavTokens.itemPaddingVertical),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LfNavTokens.itemGap),
    ) {
        // The label already names the destination and the row owns the selection
        // state, so the icon must not announce itself or TalkBack says it twice.
        Icon(imageVector = item.icon, contentDescription = null, tint = tint)
        // BUG77: at font scale 2.0 "Ledger", "Analytics" and "Profile" broke
        // mid-word ("Analy / tics") in a 72 dp cell. A label is one line, always
        // (BUG9); it steps its size down only when the whole word would not
        // otherwise fit the cell, and is the scale's size whenever it does.
        BasicText(
            text = item.label,
            style = LfNavTokens.labelStyle.merge(color = tint, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = LfNavTokens.labelMinSize,
                maxFontSize = LfNavTokens.labelStyle.fontSize,
                stepSize = LfNavTokens.labelSizeStep,
            ),
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
    Box(
        modifier = modifier
            .size(LfNavTokens.fabSize)
            .background(LfNavTokens.fabContainer, LfNavTokens.fabShape)
            .selectable(selected = false, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LfNavTokens.fabContent,
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
