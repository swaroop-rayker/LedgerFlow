package com.ledgerflow.feature.settings.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupSlice
import com.ledgerflow.core.designsystem.component.lfProseItems
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Open-source licences (Settings > About, V2-3; ADR-0032): every library,
 * font and icon set LedgerFlow ships, by name, with its version and licence.
 * About 160 rows, so they are lazy items joined into one panel
 * (`lfGroupSlice`), not a panel composed whole.
 */
@Composable
public fun LicencesScreen(
    state: LicencesUiState,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val components = state.components
    LfDetailPage(
        title = "Open-source licences",
        subtitle = components?.let { "${it.size} components LedgerFlow is built with, and their licences." },
        modifier = modifier,
        onBack = onBack,
        spacing = LfPageSpacing.None,
    ) {
        components ?: return@LfDetailPage
        items(count = components.size, key = { components[it].id }, contentType = { "licence" }) { index ->
            val component = components[index]
            LicenceRow(
                component = component,
                onClick = { onOpen(component.id) },
                modifier = Modifier.licenceSlice(lfGroupPosition(index, components.size)),
            )
        }
    }
}

/**
 * Name over version and licence, always two lines. Not `LfNavRow`, whose value
 * sits beside the title while each word fits: with a name like "IBM Plex Mono
 * (font)" beside a licence that squeezed the name to a word a line.
 */
@Composable
private fun LicenceRow(component: LicensedComponent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = component.name, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
            Text(text = component.summary(), style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
        }
        // Decoration: the row's role already says it opens something.
        Icon(imageVector = LfIcons.Chevron, contentDescription = null, tint = LfTheme.colors.text.muted)
    }
}

/**
 * One component's licences: each by name, where its full terms live, and the
 * text itself when it is bundled (Apache 2.0, BSD 3-Clause, the fonts' and
 * icons'). A URL is shown to read, not opened: nothing in About opens a
 * browser (Law 6).
 */
@Composable
public fun LicenceScreen(state: LicenceUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LfDetailPage(
        title = state.component.name,
        subtitle = state.component.version,
        modifier = modifier,
        onBack = onBack,
        spacing = LfPageSpacing.Rows,
    ) {
        state.component.licences.forEach { licence ->
            item(key = "head-${licence.id}", contentType = "licence-head") {
                Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
                    Text(
                        text = licence.name,
                        style = LfTheme.type.heading,
                        color = LfTheme.colors.text.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    licence.url?.let {
                        Text(
                            text = "Full terms: $it",
                            style = LfTheme.type.bodySmall,
                            color = LfTheme.colors.text.secondary,
                        )
                    }
                }
            }
            state.texts[licence.id]?.let { lfProseItems(it, keyPrefix = licence.id) }
        }
    }
}

/** A row's slice of the list's one panel (step F5), as Payment apps draws its rows. */
@Composable
private fun Modifier.licenceSlice(position: LfGroupPosition): Modifier = lfGroupSlice(
    position = position,
    container = LfTheme.colors.surface.raised,
    edge = LfTheme.colors.border.subtle,
    edgeWidth = LfTheme.borders.hairline,
    radius = LfLayout.panelRadius,
    dividerInset = LfTheme.space.rowInset,
)
