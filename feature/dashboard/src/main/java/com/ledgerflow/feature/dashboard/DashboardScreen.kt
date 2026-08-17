package com.ledgerflow.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Home (SPEC.md §9.3).
 *
 * The real content -- recent entries, quick stats, budget rings -- reads from
 * `daily_rollup`, which arrives with the rollup worker at P3. Until then this is
 * the shell's start destination and nothing more, and says so rather than
 * showing invented figures.
 *
 * Stateless and parameterless by design: the moment it needs data it gains a
 * `DashboardUiState` and a ViewModel, not a database call inside a composable.
 */
@Composable
public fun DashboardScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "Home")
        LfEmptyState(
            title = "Nothing here yet",
            body = "Your recent spending, budgets and quick stats appear here once " +
                "there are entries to summarise.",
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun DashboardPreview() {
    LfTheme { DashboardScreen() }
}
