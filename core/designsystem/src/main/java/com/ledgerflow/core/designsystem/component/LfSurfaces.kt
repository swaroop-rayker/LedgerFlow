package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
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
 */
@Composable
public fun LfCategoryDot(
    name: String,
    colorArgb: Int,
    modifier: Modifier = Modifier,
) {
    val initial = name.firstOrNull()?.uppercase().orEmpty()
    Box(
        modifier = modifier
            .size(LfTheme.spacing.lg)
            .background(Color(colorArgb), CircleShape)
            // The adjacent label already reads the category name aloud.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = LfTheme.typography.label,
            color = LfCategoryInk,
        )
    }
}

/** The one ink every category swatch is designed against. */
public val LfCategoryInk: Color = Color.White
