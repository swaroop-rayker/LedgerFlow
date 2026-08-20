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
    onExport: () -> Unit,
    onDeletedEntries: () -> Unit,
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
            MoreRow(
                title = "Categories & merchants",
                subtitle = "Organise how spending is grouped",
                onClick = onCategories,
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
            onExport = {},
            onDeletedEntries = {},
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
            onExport = {},
            onDeletedEntries = {},
        )
    }
}
