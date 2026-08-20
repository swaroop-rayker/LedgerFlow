package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LfTheme.spacing.lg, vertical = LfTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        Text(
            text = title,
            style = LfTheme.typography.displayL,
            color = LfTheme.colors.textPrimary,
        )
        subtitle?.let {
            Text(text = it, style = LfTheme.typography.bodyM, color = LfTheme.colors.textSecondary)
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
            .padding(LfTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        Text(
            text = title,
            style = LfTheme.typography.titleM,
            color = LfTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
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
            .height(1.dp)
            .background(LfTheme.colors.outline),
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
 * The initial is **always** [LfCategoryInk], never `LfTheme.colors.onAccent`.
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
    val initial = name.firstOrNull()?.uppercase().orEmpty()
    val diameter = LfTheme.spacing.lg
    // `Dp.toSp()` divides by the font scale, so the result renders at exactly
    // this many dp whatever the user's setting. Half the diameter is what the
    // 12sp `label` style already produced at font scale 1.0, so the swatch is
    // unchanged where it was already correct.
    val initialSize = with(LocalDensity.current) { (diameter * INITIAL_HEIGHT_RATIO).toSp() }
    Box(
        modifier = modifier
            .size(diameter)
            .background(Color(colorArgb), CircleShape)
            // The adjacent label already reads the category name aloud.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = LfTheme.typography.label.copy(
                fontSize = initialSize,
                // Unspecified so the line box follows the pinned font size. The
                // scale's 16sp line height would otherwise keep scaling and
                // push a correctly-sized glyph out of a fixed-size circle.
                lineHeight = TextUnit.Unspecified,
            ),
            color = LfCategoryInk,
        )
    }
}

/** The initial's height as a fraction of the swatch. Matches 12sp in a 24dp dot. */
private const val INITIAL_HEIGHT_RATIO = 0.5f

/** The one ink every category swatch is designed against. */
public val LfCategoryInk: Color = Color.White
