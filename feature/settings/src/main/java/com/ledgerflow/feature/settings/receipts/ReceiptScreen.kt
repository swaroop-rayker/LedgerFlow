package com.ledgerflow.feature.settings.receipts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfSkeletonList
import com.ledgerflow.core.designsystem.component.LfZoomableImage
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.feature.settings.formatSize

/**
 * One receipt (V2-5; owner's layout A with the image, 2026-11-08).
 *
 * The image fits the width and zooms; under it, what the receipt belongs to
 * and where its copies are. The actions sit on the trailing edge of the pinned
 * bar. There is no "Open entry": LedgerFlow has no page for an entry (owner,
 * 2026-11-08), so the facts it would have shown are here.
 */
@Composable
public fun ReceiptScreen(
    state: ReceiptUiState,
    onEvent: (ReceiptEvent) -> Unit,
    onReviewInInbox: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    if (state.confirmingDelete) DeleteOneDialog(state, onEvent)

    val receipt = state.receipt
    LfDetailPage(
        title = receipt?.let { ReceiptsText.title(it.filing) } ?: "Receipt",
        subtitle = receipt?.let { "${DisplayDates.mediumDateTime(it.capturedAt)} · ${formatSize(it.bytes)}" },
        modifier = modifier,
        onBack = onBack,
        actionBar = receipt?.let { { Actions(state, onEvent, onReviewInInbox) } },
        spacing = LfPageSpacing.None,
    ) {
        item(key = "image", contentType = "image") { Picture(state.image) }
        if (receipt != null) {
            item(key = "facts", contentType = "facts") { Facts(receipt.filing, state.copies) }
        }
    }
}

@Composable
private fun Picture(picture: ReceiptPicture) {
    when (picture) {
        ReceiptPicture.Loading -> LfSkeletonList()
        is ReceiptPicture.Shown -> LfZoomableImage(
            bitmap = picture.bitmap,
            contentDescription = "Receipt image. Pinch or double-tap to zoom.",
            modifier = Modifier
                .fillMaxWidth()
                .clip(LfTheme.shapes.container)
                .background(LfTheme.colors.surface.overlay),
        )
        ReceiptPicture.Unavailable -> LfNotice(
            tone = LfNoticeTone.Warning,
            title = ReceiptsText.IMAGE_UNAVAILABLE_TITLE,
            body = ReceiptsText.IMAGE_UNAVAILABLE_BODY,
        )
    }
}

/** What the receipt belongs to, then where its copies are. */
@Composable
private fun Facts(filing: ReceiptFiling, copies: ReceiptCopies?) {
    LfPanel(modifier = Modifier.padding(top = LfTheme.space.sectionGap)) {
        when (filing) {
            is ReceiptFiling.Filed -> FactRow(ReceiptsText.filedAs(filing)) {
                Text(
                    text = MoneyFormat.directional(filing.amount.minor, filing.currency, filing.ledger),
                    style = LfTheme.type.amountRow,
                    color = if (filing.ledger == LedgerType.DEBIT) {
                        LfTheme.colors.money.outgoing
                    } else {
                        LfTheme.colors.money.incoming
                    },
                    textAlign = LfTextAlign.end,
                    maxLines = 1,
                )
            }
            ReceiptFiling.InBin -> FactRow("Its entry is in the bin")
            is ReceiptFiling.InInbox -> FactRow("Waiting in the Inbox")
            ReceiptFiling.NotFiled -> FactRow("Not filed in either book")
        }
        LfDivider()
        FactRow("Copies") {
            Text(
                text = copies?.let(ReceiptsText::copies).orEmpty(),
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
                textAlign = LfTextAlign.end,
            )
        }
    }
}

/**
 * A fact and its value: side by side, or the value below when both will not
 * fit (`LfAdaptiveRow`). A plain `Row` gave the value the width and broke
 * "Copies" into a letter per line at 2.0.
 */
@Composable
private fun FactRow(title: String, trailing: (@Composable () -> Unit)? = null) {
    val space = LfTheme.space
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LfLayout.minTouchTarget)
            .padding(horizontal = space.rowInset, vertical = space.rowInsetVertical),
        contentAlignment = Alignment.CenterStart,
    ) {
        val label: @Composable () -> Unit = {
            Text(text = title, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
        }
        // LfAdaptiveRow measures one trailing node and has none to measure
        // when there is no value, so a lone title is drawn on its own.
        if (trailing == null) {
            label()
        } else {
            LfAdaptiveRow(leading = label, trailing = trailing, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The trailing edge, inline (CLAUDE.md §5). "Review in Inbox" for a receipt
 * still waiting there; "Remove from phone" joins these with schema v14
 * (ADR-0035 PR 3).
 */
@Composable
private fun Actions(state: ReceiptUiState, onEvent: (ReceiptEvent) -> Unit, onReviewInInbox: (String) -> Unit) {
    val pending = state.receipt?.filing as? ReceiptFiling.InInbox
    LfActionBar {
        LfActionRow(alignment = LfActionAlignment.End) {
            pending?.let {
                LfButton(
                    text = "Review in Inbox",
                    style = LfButtonStyle.Inline,
                    onClick = { onReviewInInbox(it.pendingId) },
                )
            }
            LfButton(
                text = "Delete",
                style = LfButtonStyle.Inline,
                enabled = !state.working,
                loading = state.working,
                onClick = { onEvent(ReceiptEvent.DeleteRequested) },
            )
        }
    }
}

@Composable
private fun DeleteOneDialog(state: ReceiptUiState, onEvent: (ReceiptEvent) -> Unit) {
    val bytes = state.receipt?.bytes ?: 0L
    LfDialog(
        title = ReceiptsText.deleteTitle(count = 1, bytes = bytes, all = false),
        body = ReceiptsText.deleteBody(state.copies ?: ReceiptCopies(backupFolder = null, drive = false), count = 1),
        confirmText = "Delete",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(ReceiptEvent.DeleteConfirmed) },
        onDismiss = { onEvent(ReceiptEvent.DeleteDismissed) },
    )
}

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ReceiptPreview() {
    LfTheme {
        ReceiptScreen(
            state = ReceiptUiState(
                receipt = previewReceipts.first(),
                image = ReceiptPicture.Unavailable,
                copies = ReceiptCopies(backupFolder = "LedgerFlow", drive = true),
            ),
            onEvent = {},
            onReviewInInbox = {},
            onBack = {},
        )
    }
}
