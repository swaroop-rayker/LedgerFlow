package com.ledgerflow.feature.settings.receipts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupSlice
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSelectionBar
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptCount
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.settings.formatSize
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Data > Receipts (V2-5, V2-PLAN §3.7; owner's layout A, 2026-11-08).
 *
 * Where the receipts are and how much space they take, the period, then one
 * panel of rows: what each receipt belongs to, when it was captured, its size,
 * and the entry's amount. A tap opens the receipt; "Select" turns the rows into
 * check boxes and the bar into what can be done to them.
 */
@Composable
public fun ReceiptsScreen(
    state: ReceiptsUiState,
    receipts: Flow<PagingData<ReceiptListItem>>,
    onEvent: (ReceiptsEvent) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReceiptsContent(
        state = state,
        items = receipts.collectAsLazyPagingItems(),
        onEvent = onEvent,
        onOpen = onOpen,
        onBack = onBack,
        modifier = modifier,
    )
}

/** [ReceiptsScreen] over pages already collected: what the tests and previews drive. */
@Composable
internal fun ReceiptsContent(
    state: ReceiptsUiState,
    items: LazyPagingItems<ReceiptListItem>,
    onEvent: (ReceiptsEvent) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.confirmation?.let { DeleteDialog(it, state.copies, onEvent) }
    // One painter for every row (CLAUDE.md §8), not one per row.
    val glyph = rememberVectorPainter(LfIcons.Receipt)

    LfDetailPage(
        title = "Receipts",
        modifier = modifier,
        onBack = onBack,
        notices = state.message?.let { message -> { DeletedNotice(message, onEvent) } },
        selectionBar = if (state.selecting) { { SelectionBar(state, onEvent) } } else null,
        // The rows draw their own slices of one panel, so the list puts no gap
        // between them; the sections pad themselves.
        spacing = LfPageSpacing.None,
    ) {
        summary(state)
        periodAndCount(state, onEvent)
        receiptRows(state, items, glyph, onEvent, onOpen)
        deleteAll(state, onEvent)
    }
}

private fun LazyListScope.summary(state: ReceiptsUiState) {
    item(key = "summary", contentType = "summary") {
        LfPanel(modifier = Modifier.padding(bottom = LfTheme.space.sectionGap)) {
            Fact(
                title = "On this phone",
                value = state.onPhone?.let { ReceiptsText.countLine(it.count, it.bytes) },
                detail = "Encrypted in LedgerFlow's own storage",
            )
            LfDivider()
            Fact(title = "Copies", value = state.copies?.let(ReceiptsText::copies))
        }
    }
}

/**
 * One fact in the summary: a title and its value, side by side or the value
 * below when both will not fit (`LfAdaptiveRow`), then a note across the width.
 */
@Composable
private fun Fact(title: String, value: String?, detail: String? = null) {
    val space = LfTheme.space
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LfLayout.minTouchTarget)
            .padding(horizontal = space.rowInset, vertical = space.rowInsetVertical),
        verticalArrangement = Arrangement.Center,
    ) {
        LfAdaptiveRow(
            leading = { Text(text = title, style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
            trailing = {
                Text(
                    text = value.orEmpty(),
                    style = LfTheme.type.bodySmall,
                    color = LfTheme.colors.text.secondary,
                    textAlign = LfTextAlign.end,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        detail?.let { Text(text = it, style = LfTheme.type.label, color = LfTheme.colors.text.muted) }
    }
}

private fun LazyListScope.periodAndCount(state: ReceiptsUiState, onEvent: (ReceiptsEvent) -> Unit) {
    item(key = "period", contentType = "period") {
        // Chips, not a segmented control (owner, 2026-11-08). Four segments
        // need 72 dp each times the font scale, which a 360 dp phone gives
        // only at exactly 1.0: at the owner's 1.15 the control stacked into a
        // column a third of the screen tall. Chips stay one short line and,
        // at large text, wrap as whole chips (BUG9).
        LfActionRow(
            alignment = LfActionAlignment.Start,
            modifier = Modifier.padding(bottom = LfTheme.space.itemGap),
        ) {
            ReceiptPeriod.entries.forEach { period ->
                LfChip(
                    label = ReceiptsText.periodLabel(period),
                    style = if (period == state.period) LfChipStyle.Selected else LfChipStyle.Assist,
                    onClick = { onEvent(ReceiptsEvent.PeriodSelected(period)) },
                )
            }
        }
    }
    item(key = "count", contentType = "count") {
        // The count on the leading edge and Select on the trailing one. The
        // count may wrap at 2.0; the button's label never does (BUG9).
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = LfTheme.space.inlineGap),
            horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.count?.let { ReceiptsText.countLine(it.count, it.bytes) }.orEmpty(),
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
                modifier = Modifier.weight(1f),
            )
            if (!state.selecting && (state.count?.count ?: 0) > 0) {
                LfButton(
                    text = "Select",
                    style = LfButtonStyle.Inline,
                    onClick = { onEvent(ReceiptsEvent.SelectionStarted) },
                )
            }
        }
    }
}

private fun LazyListScope.receiptRows(
    state: ReceiptsUiState,
    items: LazyPagingItems<ReceiptListItem>,
    glyph: Painter,
    onEvent: (ReceiptsEvent) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (items.itemCount == 0) {
        if (items.loadState.refresh is LoadState.NotLoading) {
            item(key = "empty", contentType = "empty") { NoReceipts(state.period, onEvent) }
        }
        return
    }
    items(
        count = items.itemCount,
        key = items.itemKey { it.id },
        contentType = items.itemContentType { ROW_TYPE },
    ) { index ->
        val receipt = items[index] ?: return@items
        val selected = receipt.id in state.selected
        ReceiptRow(
            receipt = receipt,
            position = lfGroupPosition(index, items.itemCount),
            glyph = glyph,
            selected = if (state.selecting) selected else null,
            onClick = {
                if (state.selecting) {
                    onEvent(ReceiptsEvent.Toggled(receipt.id, receipt.bytes))
                } else {
                    onOpen(receipt.id)
                }
            },
        )
    }
}

@Composable
private fun NoReceipts(period: ReceiptPeriod, onEvent: (ReceiptsEvent) -> Unit) {
    if (period == ReceiptPeriod.All) {
        LfEmptyState(
            title = "No receipts yet",
            body = "Receipts you scan are kept here, encrypted, with the entries they belong to.",
        )
    } else {
        LfEmptyState(
            title = "No receipts in this period",
            body = "Older receipts are under All.",
            actionLabel = "Show all",
            onAction = { onEvent(ReceiptsEvent.PeriodSelected(ReceiptPeriod.All)) },
        )
    }
}

/**
 * One receipt: a tile (or, selecting, a check box), what it belongs to, when it
 * was captured and its size, and the entry's amount in its book's colour.
 * About two lines tall, as the compactness brief asks of a scanned list.
 */
@Composable
private fun ReceiptRow(
    receipt: ReceiptListItem,
    position: LfGroupPosition,
    glyph: Painter,
    /** Null when not selecting; otherwise whether this row is ticked. */
    selected: Boolean?,
    onClick: () -> Unit,
) {
    val space = LfTheme.space
    val colors = LfTheme.colors
    val title = ReceiptsText.title(receipt.filing)
    val captured = remember(receipt.capturedAt) { capturedDay(receipt.capturedAt) }
    val detail = "$captured · ${formatSize(receipt.bytes)}"
    val filed = receipt.filing as? ReceiptFiling.Filed
    val spoken = spokenRow(title, filed, captured, receipt, selected)

    val interaction = if (selected != null) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lfGroupSlice(
                position = position,
                container = if (selected == true) colors.surface.overlay else colors.surface.raised,
                edge = colors.border.subtle,
                edgeWidth = LfTheme.borders.hairline,
                radius = LfLayout.panelRadius,
                dividerInset = space.rowInset,
            )
            .then(interaction)
            .heightIn(min = LfLayout.minTouchTarget)
            .padding(horizontal = space.rowInset, vertical = space.rowInsetVertical)
            // One announcement per row: the words below, with the amount as
            // §9.6 says it rather than as "₹1240".
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick { onClick(); true }
            },
        horizontalArrangement = Arrangement.spacedBy(space.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Leading(selected, glyph)
        // The name beside its amount, or the amount below it when both will
        // not fit (LfAdaptiveRow, BUG81): at 2.0 the name is never cut to a
        // few letters to keep the amount on its line.
        Column(modifier = Modifier.weight(1f)) {
            LfAdaptiveRow(
                leading = { Text(text = title, style = LfTheme.type.body, color = colors.text.primary) },
                trailing = { Trailing(receipt.filing) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = detail, style = LfTheme.type.figureLabel, color = colors.text.muted)
        }
    }
}

/** The receipt tile, or the check box while selecting. */
@Composable
private fun Leading(selected: Boolean?, glyph: Painter) {
    val colors = LfTheme.colors
    if (selected != null) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = colors.action.primary),
        )
    } else {
        Box(
            modifier = Modifier
                .size(LfLayout.categoryTileSize)
                .background(colors.surface.overlay, LfTheme.shapes.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = glyph, contentDescription = null, tint = colors.text.muted)
        }
    }
}

/** The entry's amount in its book's colour, or where the receipt is when it has none. */
@Composable
private fun Trailing(filing: ReceiptFiling) {
    val colors = LfTheme.colors
    if (filing is ReceiptFiling.Filed) {
        Text(
            text = MoneyFormat.directional(filing.amount.minor, filing.currency, filing.ledger),
            style = LfTheme.type.amountRow,
            color = if (filing.ledger == LedgerType.DEBIT) colors.money.outgoing else colors.money.incoming,
            textAlign = LfTextAlign.end,
            maxLines = 1,
        )
    } else {
        Text(
            text = ReceiptsText.status(filing).orEmpty(),
            style = LfTheme.type.bodySmall,
            color = colors.text.secondary,
            maxLines = 1,
        )
    }
}

private fun spokenRow(
    title: String,
    filed: ReceiptFiling.Filed?,
    captured: String,
    receipt: ReceiptListItem,
    selected: Boolean?,
): String {
    val tick = when (selected) {
        null -> null
        true -> "Selected"
        false -> "Not selected"
    }
    val amount = filed?.let { MoneyFormat.spokenDirectional(it.amount.minor, it.currency, it.ledger) }
    val status = ReceiptsText.status(receipt.filing)
    return listOfNotNull(tick, title, amount, status, "captured $captured", formatSize(receipt.bytes))
        .joinToString(", ")
}

/** "6 Nov": the day it was captured, in the user's date order. */
private fun capturedDay(millis: Long): String =
    DisplayDates.dayMonth(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())

private fun LazyListScope.deleteAll(state: ReceiptsUiState, onEvent: (ReceiptsEvent) -> Unit) {
    item(key = "delete-all", contentType = "delete-all") {
        LfPanel(modifier = Modifier.padding(top = LfTheme.space.sectionGap)) {
            Text(
                text = "Delete all receipts",
                style = LfTheme.type.body,
                color = LfTheme.colors.status.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = state.onPhone != null && !state.working,
                        role = Role.Button,
                        onClick = { onEvent(ReceiptsEvent.DeleteAllRequested) },
                    )
                    .heightIn(min = LfLayout.minTouchTarget)
                    .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
            )
        }
    }
}

/** Pinned while selecting: how many, their size, and what can be done to them. */
@Composable
private fun SelectionBar(state: ReceiptsUiState, onEvent: (ReceiptsEvent) -> Unit) {
    val count = state.selected.size
    LfSelectionBar(label = "$count selected · ${formatSize(state.selectedBytes)}") {
        LfButton(text = "Cancel", style = LfButtonStyle.Text, onClick = { onEvent(ReceiptsEvent.SelectionCancelled) })
        LfButton(text = "Select all", style = LfButtonStyle.Text, onClick = { onEvent(ReceiptsEvent.SelectAll) })
        LfButton(
            text = "Delete",
            style = LfButtonStyle.Inline,
            enabled = count > 0 && !state.working,
            loading = state.working,
            onClick = { onEvent(ReceiptsEvent.DeleteSelectedRequested) },
        )
    }
}

/**
 * The irreversible question (CLAUDE.md §7): `Warning`, the count and the size
 * in the title, and where they are removed from in the body. It never offers to
 * back up or export first (see [ReceiptsText.deleteBody]).
 */
@Composable
private fun DeleteDialog(
    confirmation: ReceiptsConfirmation,
    copies: ReceiptCopies?,
    onEvent: (ReceiptsEvent) -> Unit,
) {
    // Nothing on the phone: say so, with one answer and nothing to confirm.
    if (confirmation.count == 0) {
        LfDialog(
            title = ReceiptsText.NOTHING_TO_DELETE_TITLE,
            body = ReceiptsText.NOTHING_TO_DELETE_BODY,
            confirmText = "OK",
            dismissText = null,
            onConfirm = { onEvent(ReceiptsEvent.ConfirmationDismissed) },
            onDismiss = { onEvent(ReceiptsEvent.ConfirmationDismissed) },
        )
        return
    }
    LfDialog(
        title = ReceiptsText.deleteTitle(
            count = confirmation.count,
            bytes = confirmation.bytes,
            all = confirmation is ReceiptsConfirmation.DeleteAll,
        ),
        body = ReceiptsText.deleteBody(copies ?: ReceiptCopies(backupFolder = null, drive = false), confirmation.count),
        confirmText = "Delete",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(ReceiptsEvent.ConfirmationAccepted) },
        onDismiss = { onEvent(ReceiptsEvent.ConfirmationDismissed) },
    )
}

@Composable
private fun DeletedNotice(message: String, onEvent: (ReceiptsEvent) -> Unit) {
    LfNotice(
        tone = LfNoticeTone.Success,
        title = message,
        actionLabel = "OK",
        onAction = { onEvent(ReceiptsEvent.MessageDismissed) },
    )
}

private const val ROW_TYPE = "receipt"

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

private const val PREVIEW_NOW = 1_794_000_000_000L
private const val PREVIEW_DAY = 86_400_000L

internal val previewReceipts: List<ReceiptListItem> = listOf(
    ReceiptListItem(
        id = "r1",
        capturedAt = PREVIEW_NOW,
        bytes = 412_000,
        filing = ReceiptFiling.Filed(LedgerType.DEBIT, "Big Bazaar", "Groceries", Money(1_240_00), "INR", 0),
    ),
    ReceiptListItem(
        id = "r2",
        capturedAt = PREVIEW_NOW - 2 * PREVIEW_DAY,
        bytes = 268_000,
        filing = ReceiptFiling.Filed(LedgerType.DEBIT, "Chai Point", "Eating out", Money(180_00), "INR", 0),
    ),
    ReceiptListItem(
        id = "r3",
        capturedAt = PREVIEW_NOW - 4 * PREVIEW_DAY,
        bytes = 390_000,
        filing = ReceiptFiling.InInbox("p1"),
    ),
    ReceiptListItem(
        id = "r4",
        capturedAt = PREVIEW_NOW - 5 * PREVIEW_DAY,
        bytes = 305_000,
        filing = ReceiptFiling.NotFiled,
    ),
)

internal val previewReceiptsState = ReceiptsUiState(
    period = ReceiptPeriod.Month,
    count = ReceiptCount(count = 4, bytes = 1_375_000),
    onPhone = AttachmentUsage(count = 46, bytes = 18_400_000),
    copies = ReceiptCopies(backupFolder = "LedgerFlow", drive = true),
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ReceiptsPreview() {
    LfTheme {
        ReceiptsScreen(
            state = previewReceiptsState,
            receipts = flowOf(PagingData.from(previewReceipts)),
            onEvent = {},
            onOpen = {},
            onBack = {},
        )
    }
}
