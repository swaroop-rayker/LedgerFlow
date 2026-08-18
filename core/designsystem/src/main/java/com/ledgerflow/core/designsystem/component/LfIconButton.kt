package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * An icon-only control (SPEC.md §9.4).
 *
 * [contentDescription] is **required**, not nullable. §9.6 asks for a content
 * description on every icon-only control, and an optional parameter is a
 * requirement that gets forgotten at the one call site nobody reviewed — an
 * icon-only button with no description is a button a screen-reader user cannot
 * identify at all, which is worse than a mislabelled one.
 *
 * The touch target is the full 48dp minimum even though the glyph is smaller.
 * A tap target sized to the ink is the most common accessibility defect in a
 * compact row, and this is used inside cards where space pressure is real.
 */
@Composable
public fun LfIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LfTheme.colors.textSecondary,
) {
    val spacing = LfTheme.spacing
    Box(
        modifier = modifier.size(spacing.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(spacing.md),
            )
        }
    }
}
