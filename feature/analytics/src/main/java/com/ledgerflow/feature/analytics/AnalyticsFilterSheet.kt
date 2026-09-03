package com.ledgerflow.feature.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
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
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.picker.LfMultiPickerDialog
import com.ledgerflow.core.ui.picker.LfPickerOption
import com.ledgerflow.core.ui.picker.summariseSelection

/**
 * §5.6's composable filters, all simultaneously active.
 *
 * **Nine of the ten §5.6 lists.** The tenth — has-attachment — needs the
 * `attachment` table, which schema v10 does not have; §6.1 specifies it and it
 * lands with OCR at P4. Offering a control that silently did nothing would be
 * worse than not offering it, so it is absent and this comment is the record.
 *
 * **Each field is a row that opens the app's picker**, not a horizontally
 * scrolling strip of chips. The chips shipped first and were wrong twice over:
 * a real taxonomy is forty categories, so most of the list sat off the
 * right-hand edge with nothing on screen to say how much was there, and it was
 * a second way of choosing a category in an app whose entry form already had
 * one. `LfMultiPickerDialog` is that same dialog, with checkboxes because these
 * fields take several answers.
 */
@Composable
public fun AnalyticsFilterSheet(
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
    openField: AnalyticsFilterField?,
    onEvent: (AnalyticsEvent) -> Unit,
) {
    LfDialog(
        title = "Filters",
        body = "Narrow every figure on this screen. All of these apply together.",
        confirmText = "Done",
        onConfirm = { onEvent(AnalyticsEvent.FilterSheetShown(visible = false)) },
        onDismiss = { onEvent(AnalyticsEvent.FilterSheetShown(visible = false)) },
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

    // Over the sheet, not inside it: the same stacking the entry form uses when
    // its picker opens on top of the form it is filling in.
    if (openField != null) {
        FilterPicker(
            field = openField,
            filters = filters,
            categories = categories,
            merchants = merchants,
            onEvent = onEvent,
        )
    }
}

/**
 * The filter form.
 *
 * Scrollable and height-capped, for the reason the budget editor found on
 * device: content taller than the dialog with no scroll leaves the controls
 * below it unreachable.
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

        AnalyticsFilterField.entries.forEach { field ->
            LfDetailRow(
                label = field.label,
                value = field.summarise(filters, categories, merchants),
                onClick = { onEvent(AnalyticsEvent.FilterFieldOpened(field)) },
            )
        }

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
        modifier = Modifier.padding(top = LfTheme.spacing.sm),
    )
    LfTextField(
        value = filters.query,
        onValueChange = { onEvent(AnalyticsEvent.FiltersChanged(filters.copy(query = it))) },
        label = "Note, merchant or item",
    )
}

@Composable
private fun FilterPicker(
    field: AnalyticsFilterField,
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
    onEvent: (AnalyticsEvent) -> Unit,
) {
    val options = field.optionsFrom(filters, categories, merchants)
    LfMultiPickerDialog(
        title = field.label,
        body = field.body,
        options = options,
        selectedIds = field.selectedIn(filters),
        onToggle = { id ->
            onEvent(AnalyticsEvent.FiltersChanged(field.toggle(filters, categories, id)))
        },
        onClear = { onEvent(AnalyticsEvent.FiltersChanged(field.cleared(filters))) },
        onDismiss = { onEvent(AnalyticsEvent.FilterFieldOpened(null)) },
        emptyMessage = field.emptyMessage,
    )
}

private val AnalyticsFilterField.body: String
    get() = when (this) {
        AnalyticsFilterField.CATEGORY -> "Top-level categories in this book."
        // Mirrors the entry form's wording, and means the same thing: the list
        // narrows to the categories chosen above, when any are.
        AnalyticsFilterField.SUBCATEGORY -> "Subcategories of the categories you chose."
        AnalyticsFilterField.MERCHANT -> "Where the money went."
        AnalyticsFilterField.SOURCE -> "How the entry reached the ledger."
    }

private val AnalyticsFilterField.emptyMessage: String
    get() = when (this) {
        AnalyticsFilterField.CATEGORY -> "No categories yet. Add some in More → Organise."
        AnalyticsFilterField.SUBCATEGORY -> "Those categories have no subcategories."
        AnalyticsFilterField.MERCHANT -> "No merchants yet."
        AnalyticsFilterField.SOURCE -> "No sources."
    }

/**
 * Subcategories narrow to the chosen categories; everything else is the list.
 *
 * With no category chosen the subcategory picker offers every child rather than
 * nothing, because "filter by subcategory without first picking its parent" is
 * a reasonable thing to want and an empty list would read as a broken control.
 */
private fun AnalyticsFilterField.optionsFrom(
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
): List<LfPickerOption> = when (this) {
    AnalyticsFilterField.CATEGORY -> categories
        .filterNot { it.isSubcategory }
        .map { LfPickerOption(it.id, it.name) }

    AnalyticsFilterField.SUBCATEGORY -> categories
        .filter { it.isSubcategory }
        .filter { filters.categoryIds.isEmpty() || it.parentId in filters.categoryIds }
        .map { LfPickerOption(it.id, it.name) }

    AnalyticsFilterField.MERCHANT -> merchants.map { LfPickerOption(it.id, it.canonicalName) }

    AnalyticsFilterField.SOURCE -> EntrySource.entries.map {
        LfPickerOption(it.name, it.label())
    }
}

private fun AnalyticsFilterField.selectedIn(filters: AnalyticsFilters): Set<String> = when (this) {
    AnalyticsFilterField.CATEGORY -> filters.categoryIds
    AnalyticsFilterField.SUBCATEGORY -> filters.subcategoryIds
    AnalyticsFilterField.MERCHANT -> filters.merchantIds
    AnalyticsFilterField.SOURCE -> filters.sources.map { it.name }.toSet()
}

private fun AnalyticsFilterField.toggle(
    filters: AnalyticsFilters,
    categories: List<Category>,
    id: String,
): AnalyticsFilters = when (this) {
    AnalyticsFilterField.CATEGORY -> {
        val next = filters.categoryIds.toggle(id)
        filters.copy(categoryIds = next, subcategoryIds = filters.keptUnder(next, categories))
    }

    AnalyticsFilterField.SUBCATEGORY ->
        filters.copy(subcategoryIds = filters.subcategoryIds.toggle(id))

    AnalyticsFilterField.MERCHANT -> filters.copy(merchantIds = filters.merchantIds.toggle(id))

    AnalyticsFilterField.SOURCE -> {
        val source = EntrySource.valueOf(id)
        val next = if (source in filters.sources) {
            filters.sources - source
        } else {
            filters.sources + source
        }
        filters.copy(sources = next)
    }
}

/**
 * Chosen subcategories that still sit under a chosen category.
 *
 * **Narrowing the categories drops the subcategories it orphans.** The two
 * filters are ANDed, so a subcategory of Home left selected while only
 * Groceries is chosen matches nothing and the screen goes empty for a reason
 * the user cannot see — the orphan is invisible in the subcategory picker,
 * which lists only children of the chosen categories, while still being counted
 * in "Filters (n)".
 *
 * Resolved through each subcategory's `parentId`. Comparing the ids directly is
 * the obvious mistake and it always "succeeds": a subcategory id is never in a
 * set of category ids, so the check passes for everything and silently clears
 * the lot.
 */
private fun AnalyticsFilters.keptUnder(
    chosenCategories: Set<String>,
    categories: List<Category>,
): Set<String> {
    if (chosenCategories.isEmpty()) return subcategoryIds
    val parentOf = categories.associate { it.id to it.parentId }
    return subcategoryIds.filterTo(mutableSetOf()) { parentOf[it] in chosenCategories }
}

private fun AnalyticsFilterField.cleared(filters: AnalyticsFilters): AnalyticsFilters = when (this) {
    AnalyticsFilterField.CATEGORY -> filters.copy(categoryIds = emptySet())
    AnalyticsFilterField.SUBCATEGORY -> filters.copy(subcategoryIds = emptySet())
    AnalyticsFilterField.MERCHANT -> filters.copy(merchantIds = emptySet())
    AnalyticsFilterField.SOURCE -> filters.copy(sources = emptySet())
}

@Composable
private fun AnalyticsFilterField.summarise(
    filters: AnalyticsFilters,
    categories: List<Category>,
    merchants: List<Merchant>,
): String = summariseSelection(
    selectedIds = selectedIn(filters),
    options = optionsFrom(filters, categories, merchants),
)

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
        onDismissRequest = { onEvent(AnalyticsEvent.RangePickerShown(visible = false)) },
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
                        onEvent(AnalyticsEvent.RangePickerShown(visible = false))
                    }
                },
                style = LfButtonStyle.Inline,
            )
        },
        dismissButton = {
            LfButton(
                text = "Cancel",
                onClick = { onEvent(AnalyticsEvent.RangePickerShown(visible = false)) },
                style = LfButtonStyle.Inline,
            )
        },
    ) {
        DateRangePicker(
            state = state,
            // **Material's default header breaks mid-phrase on a real device.**
            // Seen at the owner's font scale: "Start date - End / date" wrapped
            // across two lines beside the mode-toggle pencil, which is BUG17's
            // shape exactly -- a heading competing with a control for one line.
            // Ours is short, and it says what has been picked rather than
            // labelling two empty slots.
            title = null,
            headline = { RangeHeadline(state) },
            // The pencil switches to typed-date entry, a second input path with
            // its own parsing and its own failure modes, for a range the user
            // is already picking by tapping. Dropping it also gives the
            // headline the whole line.
            showModeToggle = false,
        )
    }
}

/** What has been picked so far, on one line and in the user's own locale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeHeadline(state: DateRangePickerState) {
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    Text(
        text = when {
            start == null -> "Pick a start date"
            end == null -> pickedDateLabel(start) + " to…"
            else -> pickedDateLabel(start) + " to " + pickedDateLabel(end)
        },
        style = LfTheme.typography.titleM,
        color = LfTheme.colors.textPrimary,
        modifier = Modifier.padding(
            start = LfTheme.spacing.lg,
            end = LfTheme.spacing.lg,
            bottom = LfTheme.spacing.sm,
        ),
    )
}

/**
 * `12 Aug 2026` from the picker's UTC millis.
 *
 * UTC because that is the timezone `DateRangePicker` reports in, and reading it
 * as local time shifts every picked date by a day for anyone east of Greenwich
 * — which is everyone using this app.
 */
private fun pickedDateLabel(utcMillis: Long): String {
    val date = java.time.Instant.ofEpochMilli(utcMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
    val month = date.month.getDisplayName(
        java.time.format.TextStyle.SHORT,
        java.util.Locale.getDefault(),
    )
    return "${date.dayOfMonth} $month ${date.year}"
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
