package com.ledgerflow.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Merchant

/**
 * §5.6's composable filters, all simultaneously active.
 *
 * **Nine of the ten §5.6 lists.** The tenth — has-attachment — needs the
 * `attachment` table, which schema v10 does not have; §6.1 specifies it and it
 * lands with OCR at P4. Offering a control that silently did nothing would be
 * worse than not offering it, so it is absent and this comment is the record.
 *
 * **Multi-select rows scroll horizontally**, one line each, for the reason the
 * budget form found the hard way: a real taxonomy is forty categories, and a
 * wrapping cloud of them fills a dialog and pushes everything below it out of
 * reach.
 */
@Composable
public fun AnalyticsFilterSheet(
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
    onEvent: (AnalyticsEvent) -> Unit,
) {
    LfDialog(
        title = "Filters",
        body = "Narrow every figure on this screen. All of these apply together.",
        confirmText = "Done",
        onConfirm = { onEvent(AnalyticsEvent.FiltersDismissed) },
        onDismiss = { onEvent(AnalyticsEvent.FiltersDismissed) },
        dismissText = "Close",
        detail = {
            FilterForm(
                filters = filters,
                categories = categories,
                merchants = merchants,
                onEvent = onEvent,
            )
        },
    )
}

/**
 * The filter form.
 *
 * Scrollable and height-capped, for the reason the budget editor found on
 * device: a real taxonomy is forty categories, and content taller than the
 * dialog with no scroll leaves the controls below it unreachable.
 */
@Composable
private fun FilterForm(
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
    onEvent: (AnalyticsEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SHEET_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        // "Clear all" belongs in the content, not on the dismiss button:
        // `LfDialog`'s dismiss slot calls `onDismiss`, so a button labelled
        // "Clear all" there would close the sheet and clear nothing -- a
        // control whose label lies.
        if (!filters.isEmpty) {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Clear all (" + filters.activeCount + ")",
                    onClick = { onEvent(AnalyticsEvent.FiltersCleared) },
                    style = LfButtonStyle.Inline,
                )
            }
        }

        ChipRow(
            label = "Category",
            options = categories.map { it.id to it.name },
            selected = filters.categoryIds,
            onToggle = { id ->
                onEvent(
                    AnalyticsEvent.FiltersChanged(
                        filters.copy(categoryIds = filters.categoryIds.toggle(id)),
                    ),
                )
            },
        )
        ChipRow(
            label = "Merchant",
            options = merchants.map { it.id to it.canonicalName },
            selected = filters.merchantIds,
            onToggle = { id ->
                onEvent(
                    AnalyticsEvent.FiltersChanged(
                        filters.copy(merchantIds = filters.merchantIds.toggle(id)),
                    ),
                )
            },
        )
        ChipRow(
            label = "Source",
            options = EntrySource.entries.map { it.name to it.label() },
            selected = filters.sources.map { it.name }.toSet(),
            onToggle = { name -> onEvent(toggleSource(filters, name)) },
        )

        SearchField(filters = filters, onEvent = onEvent)
    }
}

/** §5.6's text search: note, merchant name, and item names, in one field. */
@Composable
private fun SearchField(filters: AnalyticsFilters, onEvent: (AnalyticsEvent) -> Unit) {
    Text(
        text = "Search",
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
    LfTextField(
        value = filters.query,
        onValueChange = { onEvent(AnalyticsEvent.FiltersChanged(filters.copy(query = it))) },
        label = "Note, merchant or item",
    )
}

private fun toggleSource(filters: AnalyticsFilters, name: String): AnalyticsEvent {
    val source = EntrySource.valueOf(name)
    val next = if (source in filters.sources) {
        filters.sources - source
    } else {
        filters.sources + source
    }
    return AnalyticsEvent.FiltersChanged(filters.copy(sources = next))
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (options.isEmpty()) return
    Text(
        text = label,
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        items(options, key = { it.first }) { (id, name) ->
            LfChip(
                label = name,
                style = if (id in selected) LfChipStyle.Selected else LfChipStyle.Assist,
                onClick = { onToggle(id) },
            )
        }
    }
}

/**
 * §5.6's custom range, as two dates.
 *
 * A *range* picker rather than two separate date pickers: picking a start and
 * an end in one gesture is what the user is actually doing, and two pickers
 * make it possible to leave one unset — a half-specified window with no
 * sensible meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AnalyticsRangePicker(onEvent: (AnalyticsEvent) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = { onEvent(AnalyticsEvent.CustomRangeDismissed) },
        confirmButton = {
            LfButton(
                text = "Apply",
                onClick = {
                    val from = state.selectedStartDateMillis
                    val to = state.selectedEndDateMillis
                    if (from != null && to != null) {
                        onEvent(
                            AnalyticsEvent.CustomRangePicked(
                                from = (from / MILLIS_PER_DAY).toInt(),
                                to = (to / MILLIS_PER_DAY).toInt(),
                            ),
                        )
                    } else {
                        // A half-picked range is not a range. Dismissing is
                        // honest; applying one date silently would produce a
                        // window the user did not choose.
                        onEvent(AnalyticsEvent.CustomRangeDismissed)
                    }
                },
                style = LfButtonStyle.Inline,
            )
        },
        dismissButton = {
            LfButton(
                text = "Cancel",
                onClick = { onEvent(AnalyticsEvent.CustomRangeDismissed) },
                style = LfButtonStyle.Inline,
            )
        },
    ) {
        DateRangePicker(state = state)
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/** Sentence case, mapped explicitly so a new source cannot render as a constant. */
private fun EntrySource.label(): String = when (this) {
    EntrySource.MANUAL -> "Manual"
    EntrySource.SMS -> "SMS"
    EntrySource.NOTIFICATION -> "Notification"
    EntrySource.OCR -> "Receipt"
    EntrySource.IMPORT -> "Imported"
}

private const val MILLIS_PER_DAY = 86_400_000L

private val SHEET_MAX_HEIGHT: Dp = 380.dp
