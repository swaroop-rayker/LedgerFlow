package com.ledgerflow.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.ui.entry.LfEntryRow
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * One merchant's entries in one book and window (V2-6), opened from a merchant
 * in Analytics: its name, the book and period, the total and count (the figure
 * that was tapped), then the entries in one panel, newest first, each with its
 * date.
 *
 * **Look only** (owner, 2026-11-01): no delete control on these rows, and a tap
 * does nothing, as on the Ledger. Deleting stays where the whole book is.
 */
@Composable
public fun MerchantEntriesScreen(
    state: MerchantEntriesUiState,
    entries: Flow<PagingData<LedgerListItem>>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = entries.collectAsLazyPagingItems()
    val settled = items.loadState.refresh !is LoadState.Loading
    LfDetailPage(
        title = state.merchantName,
        subtitle = state.periodLine(),
        modifier = modifier,
        onBack = onBack,
        notices = if (state.analyticsFiltered) {
            {
                LfNotice(
                    tone = LfNoticeTone.Info,
                    title = "Every entry from ${state.merchantName} in this period. " +
                        "Analytics' filters are not applied here.",
                )
            }
        } else {
            null
        },
        statusContent = if (settled && items.itemCount == 0) {
            { LfEmptyState(title = "No entries", body = "Nothing from ${state.merchantName} in this period.") }
        } else {
            null
        },
        // One panel drawn a slice per row, as the Ledger's bands are.
        spacing = LfPageSpacing.None,
    ) {
        state.total?.let { total ->
            item(key = "total", contentType = "total") { TotalLine(total, state.currency) }
        }
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            contentType = items.itemContentType { "entry" },
        ) { index ->
            val row = items[index] ?: return@items
            LfEntryRow(
                item = row,
                withDate = true,
                groupPosition = lfGroupPosition(index, items.itemCount),
            )
        }
    }
}

/** The tapped figure: the total in Plex Mono, how many entries beside it in words (ADR-0031). */
@Composable
private fun TotalLine(total: MerchantTotal, currency: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = LfTheme.space.itemGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = MoneyFormat.symbolised(total.total.minor, currency),
            style = LfTheme.type.amountRow,
            color = LfTheme.colors.text.primary,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = if (total.entries == 1) "1 entry" else "${total.entries} entries",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
        )
    }
}

/** "Expenses · 2 Oct – 31 Oct", the year shown when the period crosses one. */
private fun MerchantEntriesUiState.periodLine(): String {
    val book = if (ledger == LedgerType.DEBIT) "Expenses" else "Income"
    val start = LocalDate.ofEpochDay(from.toLong())
    val end = LocalDate.ofEpochDay(to.toLong())
    val period = if (start.year == end.year) {
        DisplayDates.dayMonth(start) + RANGE_DASH + DisplayDates.dayMonth(end)
    } else {
        DisplayDates.dayMonthYear(start) + RANGE_DASH + DisplayDates.dayMonthYear(end)
    }
    return book + SEPARATOR + period
}

/** An en dash between spaces, and a middle dot, as code points (CLAUDE.md §5). */
private val RANGE_DASH: String = " " + Char(0x2013) + " "
private val SEPARATOR: String = " " + Char(0x00B7) + " "

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun MerchantEntriesPreview() {
    val day = LocalDate.of(2026, 10, 28).toEpochDay().toInt()
    val rows = listOf(
        LedgerListItem(
            id = "1", ledger = LedgerType.DEBIT, amount = Money(62_000), currency = "INR",
            occurredAt = 0L, localDate = day, categoryName = "Dining out", categoryColorArgb = null,
            merchantName = "Swiggy", note = "dinner",
        ),
    )
    LfTheme {
        MerchantEntriesScreen(
            state = MerchantEntriesUiState(
                ledger = LedgerType.DEBIT,
                merchantName = "Swiggy",
                from = day - 26,
                to = day + 3,
                analyticsFiltered = false,
                total = MerchantTotal(Money(62_000), 1),
            ),
            entries = flowOf(PagingData.from(rows)),
            onBack = {},
        )
    }
}
