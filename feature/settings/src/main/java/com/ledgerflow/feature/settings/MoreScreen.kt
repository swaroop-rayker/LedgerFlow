package com.ledgerflow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth

/**
 * The "More" tab (SPEC.md §9.3): everything that is not one of the three main
 * surfaces.
 *
 * Navigation callbacks rather than routes -- the shell owns the graph, so this
 * module never learns that `:feature:categories` exists.
 */
@Composable
public fun MoreScreen(
    state: MoreUiState,
    onCategories: () -> Unit,
    onBudgets: () -> Unit,
    onExport: () -> Unit,
    onDeletedEntries: () -> Unit,
    onNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "More")
        Column(
            modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            // §5.2. First, because it is the only row that can be *wrong* --
            // the others open something that always works. It is also the
            // standing route back to the explainer once the first-run
            // presentation has been dismissed, so it is always present and
            // always enabled, for the reason the bin row below records.
            MoreRow(
                title = "Notification capture",
                subtitle = captureSubtitle(state),
                onClick = onNotificationAccess,
            )
            MoreRow(
                title = "Categories & merchants",
                subtitle = "Organise how spending is grouped",
                onClick = onCategories,
            )
            MoreRow(
                title = "Budgets",
                subtitle = "Set a limit per category",
                onClick = onBudgets,
            )
            MoreRow(
                title = "Export",
                subtitle = "Save your data as CSV",
                onClick = onExport,
            )
            // **"Deleted entries", not "Erase deleted entries".** The row used
            // to perform the erase itself, so it was named for the verb; it now
            // opens the bin, and a row that navigates takes a noun. Naming a
            // destination after the most destructive thing you can do inside it
            // is how you get people afraid to open it.
            //
            // Not "Recently deleted" either, for all that the platform leans
            // that way: nothing here expires on a timer, and the word would
            // promise a sweep the app does not perform.
            //
            // Always present and always enabled, even at zero. It was hidden
            // when there was nothing to erase, and that was reported as the
            // feature being missing -- a control that exists only sometimes is
            // indistinguishable from one that was never built.
            MoreRow(
                title = "Deleted entries",
                subtitle = deletedSubtitle(state),
                onClick = onDeletedEntries,
            )
        }
    }
}

/**
 * What the bin row says about itself.
 *
 * The zero case has to explain what the row is *for*, not merely that it is
 * empty: that is the state a user reading Settings to learn what the app can do
 * will normally find it in.
 */
internal fun deletedSubtitle(state: MoreUiState): String = when {
    !state.isLoaded -> "Restore or permanently erase deleted entries"
    state.deletedCount == 0 -> "Nothing deleted. Entries you delete are kept here."
    state.deletedCount == 1 -> "1 entry kept here. Restore it or erase it for good."
    else -> "${state.deletedCount} entries kept here. Restore them or erase them for good."
}

/**
 * What the notification row says about itself (SPEC.md §5.2).
 *
 * Each state names the *consequence* rather than the mechanism, because the
 * mechanism is what the screen behind the row is for. "Off" alone would not tell
 * the user that payments are being missed, which is the only fact that makes the
 * row worth tapping.
 *
 * [NotificationCaptureHealth.RECONNECTING] is the pre-poll value as well as a
 * real transient state, so its sentence has to be true of both: it says what the
 * row is for and claims nothing about the current grant.
 */
internal fun captureSubtitle(state: MoreUiState): String = when (state.captureHealth) {
    NotificationCaptureHealth.CONNECTED -> "On. Payment notifications reach your Inbox."
    NotificationCaptureHealth.NOT_GRANTED ->
        "Off. Payments that only send a notification are being missed."

    NotificationCaptureHealth.DEAD ->
        "Stopped. Android has not reconnected the listener — tap to check."

    NotificationCaptureHealth.RECONNECTING ->
        "Read payment notifications, and what LedgerFlow does with them."

    // Unreachable for notifications -- both flavours ship the listener and every
    // supported device can host one -- but an enum `when` may not have an `else`
    // (CLAUDE.md §5), and a sentence is cheaper than a lie.
    NotificationCaptureHealth.UNAVAILABLE ->
        "Not available on this device."
}

@Composable
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    LfCard(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun MorePreview() {
    LfTheme {
        MoreScreen(
            state = MoreUiState(deletedCount = 3, isLoaded = true),
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
        )
    }
}

/** An empty bin — the state most users see most of the time. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun MoreEmptyBinPreview() {
    LfTheme {
        MoreScreen(
            state = MoreUiState(deletedCount = 0, isLoaded = true),
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
        )
    }
}
