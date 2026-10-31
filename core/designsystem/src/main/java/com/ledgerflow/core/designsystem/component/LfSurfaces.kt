package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign

/**
 * Screen title, as a plain block rather than Material's `TopAppBar`.
 *
 * §9.3 wants a collapsing large title on Dashboard and Analytics. That needs
 * `TopAppBarScrollBehavior` wired to each screen's scroll state, which is a
 * per-screen decision the screens do not exist to make yet. This renders the
 * large-title *typography* now so the layout is honest, and the collapse
 * behaviour lands with the screens that scroll enough to need it.
 */
@Composable
public fun LfScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    LfTitleBlock(
        title = title,
        subtitle = subtitle,
        modifier = modifier.fillMaxWidth().padding(horizontal = LfScreenTitleTokens.paddingHorizontal),
    )
}

/**
 * [LfScreenTitle] without its own side margin, for a page template that
 * already applies the screen edge to everything it lays out.
 */
@Composable
internal fun LfTitleBlock(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LfScreenTitleTokens.paddingVertical),
        verticalArrangement = Arrangement.spacedBy(LfScreenTitleTokens.gap),
    ) {
        Text(
            text = title,
            style = LfScreenTitleTokens.titleStyle,
            color = LfScreenTitleTokens.title,
        )
        subtitle?.let {
            Text(text = it, style = LfScreenTitleTokens.subtitleStyle, color = LfScreenTitleTokens.subtitle)
        }
    }
}

/**
 * The empty state.
 *
 * Takes an action rather than only a message: an empty screen that does not say
 * what to do next is a dead end, and in this app most empty states are the
 * user's very first view of a feature.
 */
@Composable
public fun LfEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(LfEmptyStateTokens.padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LfEmptyStateTokens.gap),
    ) {
        Text(
            text = title,
            style = LfEmptyStateTokens.titleStyle,
            color = LfEmptyStateTokens.title,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = LfEmptyStateTokens.bodyStyle,
            color = LfEmptyStateTokens.body,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            LfButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * A hairline rule.
 *
 * Its job in a card is to separate content from the actions beneath it, so the
 * buttons read as a distinct region rather than as more of the same block. Uses
 * the `outline` token, the same colour that draws every card's own edge, so
 * dividers and borders never disagree by a shade.
 */
@Composable
public fun LfDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LfDividerTokens.thickness)
            .background(LfDividerTokens.color),
    )
}

/**
 * A category's colour swatch, carrying its initial.
 *
 * Categories are identified by colour plus initial rather than by an icon.
 * `material-icons-core` has nothing suitable for "Groceries" or "Fuel", and
 * `material-icons-extended` is a large artifact to add for decoration against
 * the §11 budget. The `category.icon` column still exists and is still written,
 * so a real icon pack can be adopted later without a migration.
 *
 * Colour alone would fail §9.6 for colour-blind users, which is exactly why the
 * initial is inside the dot rather than beside it.
 *
 * The initial is **always** [LfCategoryInk], never `LfTheme.colors.action.onPrimary`.
 * Using the theme's on-accent looked right and was not: in dark mode that token
 * is near-black, and no colour can clear 4.5:1 against both near-black and
 * near-white — the two ratios multiply to about 21, so demanding 4.5 of each is
 * arithmetically impossible. The swatch does not change between themes, so
 * neither may its ink. `CategoryPaletteContrastTest` pins the pairing.
 *
 * **The initial does not follow the user's font scale, and that is deliberate.**
 * It is part of an icon, not a piece of text: it carries no information the
 * adjacent label does not already carry, which is why it is excluded from
 * semantics entirely. Sized in `sp` it scaled while the 24dp circle did not, so
 * at font scale 2.0 the glyph's em box exactly matched the circle's *diameter*
 * and the letter was clipped by the curve on all four sides — visible on device
 * in the Ledger list, where every row has one. §9.6 wants 2.0 without
 * clipping, and the fix for a decorative glyph is to pin it to the shape it
 * lives inside rather than to the text scale.
 */
@Composable
public fun LfCategoryDot(
    name: String,
    colorArgb: Int,
    modifier: Modifier = Modifier,
) {
    // One node, drawn: this used to be a `Box` around a `Text`, two layout nodes
    // and a text node per swatch, and the Ledger composes one per row inside
    // the frame that scrolls it in (PERF-120HZ-PLAN §2.2).
    Spacer(modifier = modifier.size(LfCategoryDotTokens.size).lfCategoryDot(name, colorArgb))
}

/**
 * Draws [LfCategoryDot] into the bounds of the node this modifies, for a caller
 * that already has a node of the right size and would otherwise nest a swatch
 * inside it -- the entry row's swatch slot. Size it 24 dp square, [LfCategoryDot]'s
 * own size: the initial is half the node's width, as the dot's always was.
 *
 * Carries no semantics, for the reason [LfCategoryDot] gives: the adjacent
 * label already reads the category name aloud.
 */
public fun Modifier.lfCategoryDot(name: String, colorArgb: Int): Modifier =
    this
        .clearAndSetSemantics {}
        .then(
            CategoryDotElement(
                initial = name.firstOrNull()?.uppercase().orEmpty(),
                color = Color(colorArgb),
                ink = LfCategoryInk,
            ),
        )

/**
 * Draws the category's tinted tile (step F3, ADR-0031) into the bounds of the
 * node this modifies: the reference samples' icon tile, carrying the initial
 * until F4 brings icons. The entry row's swatch slot; size it 40 dp square.
 * No semantics, for the reason [LfCategoryDot] gives.
 */
public fun Modifier.lfCategoryTile(name: String, colorArgb: Int): Modifier =
    this
        .clearAndSetSemantics {}
        .then(
            CategoryDotElement(
                initial = name.firstOrNull()?.uppercase().orEmpty(),
                color = Color(colorArgb),
                ink = LfCategoryInk,
                tile = true,
            ),
        )

/** The one ink every category swatch is designed against. */
public val LfCategoryInk: Color = Color.White
