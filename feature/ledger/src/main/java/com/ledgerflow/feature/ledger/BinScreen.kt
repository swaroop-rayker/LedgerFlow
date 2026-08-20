package com.ledgerflow.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * The bin (SPEC.md §5.5, ADR-0015).
 *
 * Everything deleted, from **both books at once** — the one place in the app
 * that mixes them. ADR-0015 records why that is not a Law 2 breach: the two
 * queries underneath are one per ledger, nothing is summed, and each row signs
 * and colours itself from its own `ledger`. What Law 2 forbids is a combined
 * figure, and there is no figure on this screen.
 *
 * Restore is offered without a confirmation and purge is not. That asymmetry is
 * the whole design: putting something back is undoable by binning it again,
 * while erasing it is the only thing in the app that cannot be walked back.
 */
@Composable
public fun BinScreen(
    state: BinUiState,
    onEvent: (BinEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.confirmation?.let { BinConfirmationDialog(it, onEvent) }

    LfScaffold(
        modifier = modifier,
        bottomBar = { if (state.entries.isNotEmpty()) BinActions(state, onEvent) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LfScreenTitle(
                    title = "Deleted entries",
                    subtitle = subtitleFor(state),
                    modifier = Modifier.weight(1f),
                )
                LfButton(
                    text = "Done",
                    style = LfButtonStyle.Text,
                    onClick = onBack,
                    modifier = Modifier.padding(end = LfTheme.spacing.md),
                )
            }

            state.message?.let { BinMessage(it, onEvent) }

            BinList(state, onEvent)
        }
    }
}

/**
 * What the header says about the bin.
 *
 * The count when there is a selection, because that is what the actions below
 * will act on and the user should not have to count ticks themselves.
 */
private fun subtitleFor(state: BinUiState): String? = when {
    !state.isLoaded -> null
    state.entries.isEmpty() -> null
    state.hasSelection -> "${state.selectionCount} of ${state.entries.size} selected"
    else -> "${state.entries.size} deleted ${if (state.entries.size == 1) "entry" else "entries"}"
}

@Composable
private fun BinList(state: BinUiState, onEvent: (BinEvent) -> Unit) {
    if (!state.isLoaded) return

    if (state.entries.isEmpty()) {
        LfEmptyState(
            title = "Nothing deleted",
            body = "Entries you delete are kept here until you erase them. " +
                "Nothing is lost in the meantime — they just stop counting " +
                "towards your ledger and totals.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        contentPadding = PaddingValues(bottom = LfTheme.spacing.md),
    ) {
        items(
            count = state.entries.size,
            key = { state.entries[it].selectionKey() },
            contentType = { BIN_ROW_TYPE },
        ) { index ->
            val entry = state.entries[index]
            BinRow(
                entry = entry,
                selected = entry.selectionKey() in state.selected,
                enabled = !state.isWorking,
                onEvent = onEvent,
            )
        }
    }
}

/**
 * One binned entry.
 *
 * The same card the Ledger uses, plus a checkbox and minus the swatch's
 * privileged position — this list is mixed, so the row has to carry its own
 * direction rather than inherit it from the tab.
 *
 * The whole row toggles, not just the box. A row whose only target is a 20dp
 * checkbox is a row people tap and nothing happens.
 */
@Composable
private fun BinRow(
    entry: DeletedEntry,
    selected: Boolean,
    enabled: Boolean,
    onEvent: (BinEvent) -> Unit,
) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerLarge)

    val title = listOfNotNull(entry.merchantName, entry.categoryName, entry.subcategoryName)
        .joinToString(BIN_SEPARATOR)
        .ifEmpty { "Unfiled" }
    // Always with the date: a bin spans whatever the user has deleted,
    // and there is no band header above a row to carry it.
    val stamp = occurredStamp(entry.occurredAt, withDate = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(
                HAIRLINE.dp,
                if (selected) colors.accent else colors.outline,
                shape,
            )
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onEvent(BinEvent.Toggled(entry.selectionKey())) },
            )
            .padding(start = spacing.sm, end = spacing.md, top = spacing.sm, bottom = spacing.sm)
            // One announcement for the row. The checkbox inside it would
            // otherwise be a second, unlabelled node, and TalkBack would read
            // the state twice without ever saying what it belonged to.
            .clearAndSetSemantics {
                contentDescription = entry.spokenAs(title, stamp, selected)
            },
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = colors.accent),
        )

        BinSwatch(entry)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = colors.textPrimary,
            )
            EntryRowBody(
                leading = { BinStamp(stamp) },
                trailing = { BinAmount(entry) },
            )
        }
    }
}

/** When the entry happened. Always dated — a bin has no band headers. */
@Composable
private fun BinStamp(stamp: String) {
    Text(
        text = stamp,
        style = LfTheme.typography.label,
        color = LfTheme.colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * How much, and which way.
 *
 * **Signed and coloured from the row's own book.** On every other screen the
 * selected tab settles direction for the whole list; this one is mixed, so the
 * row is the only thing that knows which way its amount points. That is exactly
 * why [com.ledgerflow.core.model.DeletedEntry] carries `ledger` at all
 * (ADR-0015).
 */
@Composable
private fun BinAmount(entry: DeletedEntry) {
    val colors = LfTheme.colors
    Text(
        text = MoneyFormat.directional(entry.amount.minor, entry.currency, entry.ledger),
        style = LfTheme.typography.amountM,
        color = if (entry.ledger == LedgerType.DEBIT) colors.debit else colors.credit,
        textAlign = TextAlign.End,
    )
}

/** The category's colour, aligned to the naming line as it is on the Ledger. */
@Composable
private fun BinSwatch(entry: DeletedEntry) {
    val namingLine = with(LocalDensity.current) {
        LfTheme.typography.bodyL.lineHeight.toDp()
    }
    Box(modifier = Modifier.height(namingLine), contentAlignment = Alignment.Center) {
        val name = entry.categoryName
        val color = entry.categoryColorArgb
        if (name != null && color != null) {
            LfCategoryDot(name = name, colorArgb = color)
        } else {
            Box(
                modifier = Modifier
                    .size(LfTheme.spacing.lg)
                    .border(HAIRLINE.dp, LfTheme.colors.outline, CircleShape),
            )
        }
    }
}

/**
 * The pinned actions.
 *
 * `LfActionRow`, so at large font scales whole buttons wrap rather than labels
 * clipping (BUG9). "Erase all" stays available with nothing ticked — it is the
 * one action that does not need a selection — while the two that act on chosen
 * rows disable until there are some.
 */
@Composable
private fun BinActions(state: BinUiState, onEvent: (BinEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LfTheme.spacing.md,
                end = LfTheme.spacing.md,
                top = LfTheme.spacing.xs,
                bottom = LfTheme.spacing.xs,
            ),
    ) {
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = if (state.selectionCount == state.entries.size) "None" else "All",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(BinEvent.SelectAllToggled) },
            )
            LfButton(
                text = "Erase all",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(BinEvent.PurgeAllRequested) },
            )
            LfButton(
                text = "Erase",
                style = LfButtonStyle.Inline,
                enabled = state.hasSelection && !state.isWorking,
                onClick = { onEvent(BinEvent.PurgeSelectedRequested) },
            )
            LfButton(
                text = "Restore",
                enabled = state.hasSelection && !state.isWorking,
                loading = state.isWorking,
                onClick = { onEvent(BinEvent.RestoreRequested) },
            )
        }
    }
}

/**
 * The two destructive questions.
 *
 * Both `Warning`, the emphasis otherwise reserved for the Recovery Kit: these
 * are the only operations in the app that cannot be undone, and a dialog that
 * looks like every other dialog is one people tap through.
 *
 * Neither offers to back up first. `.lfbk` is phrase-derived (ADR-0011) and the
 * app never holds the 24 words, so it says so rather than promising a safety
 * net it cannot provide.
 */
@Composable
private fun BinConfirmationDialog(confirmation: BinConfirmation, onEvent: (BinEvent) -> Unit) {
    val (title, body) = when (confirmation) {
        is BinConfirmation.PurgeSelected -> {
            val noun = if (confirmation.count == 1) "entry" else "entries"
            "Erase ${confirmation.count} $noun?" to
                "This removes them from the database for good, with their line " +
                "items. There is no undo. Export first if you might want them."
        }
        is BinConfirmation.PurgeAll -> {
            val noun = if (confirmation.count == 1) "entry" else "entries"
            "Erase all ${confirmation.count} $noun?" to
                "This empties the bin for good, with every line item in it. " +
                "There is no undo. Export first if you might want them."
        }
    }
    LfDialog(
        title = title,
        body = body,
        confirmText = "Erase for good",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(BinEvent.ConfirmationAccepted) },
        onDismiss = { onEvent(BinEvent.ConfirmationDismissed) },
    )
}

/** What happened, in a line the user can dismiss. */
@Composable
private fun BinMessage(message: String, onEvent: (BinEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LfTheme.spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        LfButton(
            text = "OK",
            style = LfButtonStyle.Text,
            onClick = { onEvent(BinEvent.MessageDismissed) },
        )
    }
}

/**
 * What TalkBack says for a binned row.
 *
 * The direction becomes a word: screen readers skip `-`/`+` as reliably as they
 * skip `₹`, and on a mixed list that is the one thing a row cannot afford to
 * lose (§9.6). The tick state is spoken too, since the visual cue is a checkbox
 * this description replaces.
 */
private fun DeletedEntry.spokenAs(title: String, stamp: String, selected: Boolean): String {
    val state = if (selected) "Selected" else "Not selected"
    val amount = MoneyFormat.spokenDirectional(amount.minor, currency, ledger)
    return "$state. $amount, $title, $stamp."
}

private const val BIN_ROW_TYPE = "binned"
private const val BIN_SEPARATOR = " · "
