package com.ledgerflow.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.statement.BookShare
import com.ledgerflow.core.domain.statement.ImportBatch
import com.ledgerflow.core.model.LedgerType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One import with rows still waiting, above the queue (V2-4, D8; owner's
 * placement A, 2026-11-06): what the file was, how many rows "Approve all"
 * takes and how many it leaves, and the action. Two lines and an action row,
 * in the queue's own panel shape.
 *
 * **The action only opens the confirmation**, which names the count and each
 * book's total; that dialog's Approve is the tap Law 1 asks for.
 */
@Composable
internal fun ImportBatchPanel(
    batch: ImportBatch,
    enabled: Boolean,
    onEvent: (InboxEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LfPanel(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        ) {
            Text(text = "Imported from a statement", style = LfTheme.type.label, color = LfTheme.colors.text.primary)
            Text(
                text = batch.source(),
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
                // A bank's file name can be long; see [source] for why the date leads.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LfDivider()
        LfAdaptiveRow(
            leading = {
                Text(text = batch.readyLine(), style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            },
            trailing = {
                LfButton(
                    text = "Approve ${batch.ready}",
                    style = LfButtonStyle.Inline,
                    enabled = enabled,
                    onClick = { onEvent(InboxEvent.ApproveImportRequested(batch.fingerprint)) },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        )
    }
}

/**
 * D8's confirmation: the count, and each book on its own line with its own
 * total (Law 2), what the entries will hold, and what stays behind. Normal
 * emphasis: nothing is destroyed, and every entry can be binned afterwards.
 */
@Composable
internal fun ApproveImportDialog(batch: ImportBatch, onEvent: (InboxEvent) -> Unit) {
    LfDialog(
        title = "Approve ${batch.ready} ${if (batch.ready == 1) "row" else "rows"}?",
        body = (batch.fileName?.let { "From $it. " } ?: "") +
            "Each goes to your ledger as it was read, with its description as the note, " +
            "and with a merchant only where one already matches.",
        confirmText = "Approve ${batch.ready}",
        onConfirm = { onEvent(InboxEvent.ApproveImportConfirmed) },
        onDismiss = { onEvent(InboxEvent.ApproveImportDismissed) },
        detail = {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
                BookLine("Expenses", batch.spent, batch.baseCurrency, LedgerType.DEBIT)
                BookLine("Income", batch.received, batch.baseCurrency, LedgerType.CREDIT)
                if (batch.toReview > 0) {
                    Text(
                        text = "${batch.toReview} ${if (batch.toReview == 1) "row stays" else "rows stay"} " +
                            "in the Inbox to review one by one.",
                        style = LfTheme.type.bodySmall,
                        color = LfTheme.colors.text.secondary,
                    )
                }
            }
        },
    )
}

/** One book's count and total, in that book's colour. A book with no rows is not shown. */
@Composable
private fun BookLine(label: String, share: BookShare, currency: String, book: LedgerType) {
    if (share.count == 0) return
    LfAdaptiveRow(
        leading = {
            Text(text = "$label · ${share.count}", style = LfTheme.type.bodySmall, color = LfTheme.colors.text.primary)
        },
        trailing = {
            Text(
                text = MoneyFormat.directional(share.total.minor, currency, book),
                style = LfTheme.type.figureLabel,
                color = when (book) {
                    LedgerType.DEBIT -> LfTheme.colors.money.outgoing
                    LedgerType.CREDIT -> LfTheme.colors.money.incoming
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * "5 Nov · Example-Bank-Statement-Oct-2026.csv", or as much of it as was
 * noted. The date first: at font scale 2.0 a bank's file name runs past two
 * lines, and the ellipsis should take the file name's tail, not the date. Day
 * and month in the user's order, as the rows below it write theirs.
 */
internal fun ImportBatch.source(): String =
    listOfNotNull(importedAt?.let { DisplayDates.dayMonth(localDateOf(it)) }, fileName)
        .joinToString(" · ")
        .ifEmpty { "An earlier import" }

/** "14 ready · 2 to review". */
internal fun ImportBatch.readyLine(): String =
    if (toReview == 0) "$ready ready" else "$ready ready · $toReview to review"

private fun localDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
