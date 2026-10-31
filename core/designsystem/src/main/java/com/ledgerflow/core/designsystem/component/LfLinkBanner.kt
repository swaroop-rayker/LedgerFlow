package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * One line that goes somewhere: the reference samples' link banner ("See your
 * financial report", step F5). Home's "3 waiting in the Inbox" and "See this
 * month in Analytics".
 *
 * An [LfNavRow] on the raised overlay in the control shape, so it reads as a
 * tappable band rather than a card holding a link: the whole line is the
 * target, 48 dp tall at least, with the chevron that every way onward in the
 * app carries.
 */
@Composable
public fun LfLinkBanner(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfNavRow(
        title = title,
        onClick = onClick,
        modifier = modifier
            .clip(LfTheme.shapes.control)
            .background(LfTheme.colors.surface.overlay),
    )
}
