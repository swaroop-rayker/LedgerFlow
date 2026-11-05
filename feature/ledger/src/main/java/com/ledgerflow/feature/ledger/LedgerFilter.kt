package com.ledgerflow.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfFilterButton
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.MerchantCategoryBreakdown
import com.ledgerflow.core.domain.taxonomy.UNCATEGORISED_MERCHANTS
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.picker.LfMultiPickerDialog
import com.ledgerflow.core.ui.picker.LfPickerOption

/**
 * The Ledger's header band: the book switch, the merchant-category filter
 * beside it (V2-6, V2-PLAN §5.3), and while a filter is on, a chip naming it
 * that clears it. The Ledger's first filter: the control is Analytics', so a
 * later dimension joins the same button rather than a second one.
 */
@Composable
internal fun LedgerHeaderBand(state: LedgerUiState, onEvent: (LedgerEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LfSegmentedControl(
                options = listOf("Expenses", "Income"),
                selectedIndex = state.ledger.ordinal,
                onSelect = { onEvent(LedgerEvent.LedgerSelected(LedgerType.entries[it])) },
                modifier = Modifier.weight(1f),
            )
            LfFilterButton(
                activeFilters = state.merchantCategoryFilter.size,
                onClick = { onEvent(LedgerEvent.FilterOpened) },
            )
        }
        if (state.merchantCategoryFilter.isNotEmpty()) {
            LfActionRow(alignment = LfActionAlignment.Start) {
                val names = state.chosenNames()
                LfChip(
                    // One line, never wrapped (BUG9): the first name and how many more.
                    label = names.first() + (if (names.size > 1) " +${names.size - 1}" else "") + CLEAR_MARK,
                    style = LfChipStyle.Selected,
                    contentDescription = "Merchant category: ${names.joinToString()}. Clears the filter.",
                    onClick = { onEvent(LedgerEvent.FilterCleared) },
                )
            }
        }
    }
}

/** The chosen categories' names, in the picker's order: live categories, then Uncategorised. */
private fun LedgerUiState.chosenNames(): List<String> =
    merchantCategoryOptions().filter { it.id in merchantCategoryFilter }.map { it.label }
        .ifEmpty { listOf(UNCATEGORISED_MERCHANTS) }

/** The live merchant categories, then Uncategorised, as Analytics' filter offers them. */
private fun LedgerUiState.merchantCategoryOptions(): List<LfPickerOption> =
    merchantCategories.map { LfPickerOption(it.id, it.name) } +
        LfPickerOption(MerchantCategoryBreakdown.UNCATEGORISED_ID, UNCATEGORISED_MERCHANTS)

/** The filter's picker: the same multi-select dialog Analytics' filter sheet opens. */
@Composable
internal fun MerchantCategoryFilterPicker(state: LedgerUiState, onEvent: (LedgerEvent) -> Unit) {
    LfMultiPickerDialog(
        title = "Merchant category",
        body = "Entries from merchants of these kinds.",
        options = state.merchantCategoryOptions(),
        selectedIds = state.merchantCategoryFilter,
        onToggle = { onEvent(LedgerEvent.FilterToggled(it)) },
        onClear = { onEvent(LedgerEvent.FilterCleared) },
        onDismiss = { onEvent(LedgerEvent.FilterClosed) },
    )
}

/** Nothing in the window matches: says which filter, and offers the way out. */
@Composable
internal fun FilteredEmpty(state: LedgerUiState, onEvent: (LedgerEvent) -> Unit) {
    LfEmptyState(
        title = "No entries from these kinds of place",
        body = "Nothing in the last ${state.windowDays} days came from a merchant filed under " +
            state.chosenNames().joinToString() + ".",
        actionLabel = "Clear filter",
        onAction = { onEvent(LedgerEvent.FilterCleared) },
    )
}

/** A multiplication sign after a space, as a code point (CLAUDE.md §5): the chip clears. */
private val CLEAR_MARK: String = " " + Char(0x2715)
