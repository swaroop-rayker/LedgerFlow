package com.ledgerflow.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState

/**
 * Destinations whose feature modules land in later steps.
 *
 * They exist so the nav graph is complete and navigable now -- a graph with
 * missing edges cannot be tested, and a back-stack bug found after four screens
 * are built is four screens' worth of debugging. Each says plainly which step
 * fills it in, so none can be mistaken for finished work.
 */
@Composable
internal fun EntryPlaceholder(onBack: () -> Unit) {
    Placeholder(
        title = "Add an entry",
        body = "The manual entry form, both ledgers, the line-item editor and " +
            "draft persistence arrive with :feature:entry.",
        onBack = onBack,
    )
}

@Composable
internal fun ExportPlaceholder(onBack: () -> Unit) {
    Placeholder(
        title = "Export",
        body = "CSV export to a folder you choose arrives with :feature:export.",
        onBack = onBack,
    )
}

@Composable
private fun Placeholder(title: String, body: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LfEmptyState(title = title, body = body)
        LfButton(text = "Back", onClick = onBack, style = LfButtonStyle.Text)
    }
}

