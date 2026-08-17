package com.ledgerflow.feature.ledger

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
 * The two ledgers (SPEC.md §9.3, Law 2).
 *
 * When this fills in it gets an `Expenses | Income` segmented control and **two
 * separate Paging flows**, never one list with a sign column and never a
 * combined total. The tab is a partition selector, not a filter over shared
 * data -- that distinction is the whole of ADR-0002 at the UI layer.
 */
@Composable
public fun LedgerScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "Ledger")
        LfEmptyState(
            title = "No entries yet",
            body = "Expenses and income are kept as two separate books. Add an entry " +
                "with the button below and it appears in whichever one you chose.",
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun LedgerPreview() {
    LfTheme { LedgerScreen() }
}
