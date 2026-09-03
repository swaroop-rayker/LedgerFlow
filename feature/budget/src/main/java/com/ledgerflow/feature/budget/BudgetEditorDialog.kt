package com.ledgerflow.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfSwitchRow
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.picker.LfPickerDialog
import com.ledgerflow.core.ui.picker.LfPickerOption

/**
 * Create or edit a budget (`SPEC.md` §5.7).
 *
 * **The category cannot be changed on an edit**, and the picker is simply
 * absent then. Re-pointing a budget at a different category silently rewrites
 * what every past figure meant — the honest operation is to delete this one and
 * make another, which the list already offers.
 *
 * **Only unbudgeted categories are offered.** §5.7 allows one budget per
 * category, so an option that can only fail is not an option; the ViewModel
 * filters the list and the dialog just renders it.
 */
@Composable
public fun BudgetEditorDialog(
    editor: BudgetEditorState,
    availableCategories: List<Category>,
    subcategories: List<Category>,
    currency: String,
    onEvent: (BudgetEvent) -> Unit,
) {
    if (editor.showDatePicker) {
        StartDatePicker(
            selected = editor.startDate,
            onPicked = { onEvent(BudgetEvent.StartDatePicked(it)) },
            onDismiss = { onEvent(BudgetEvent.DatePickerDismissed) },
        )
    }

    BudgetPickers(
        editor = editor,
        availableCategories = availableCategories,
        subcategories = subcategories,
        onEvent = onEvent,
    )

    LfDialog(
        title = if (editor.isEdit) "Edit budget" else "New budget",
        body = if (editor.isEdit) {
            // Says "period" rather than only "how much", because since Q20 the
            // dialog changes the window too -- and moving it re-cuts the period
            // in flight, which the copy should not spring on anyone.
            "Change the limit for ${editor.categoryName}, or the period it runs over."
        } else {
            "Pick a category and set a limit for the period."
        },
        confirmText = "Save",
        onConfirm = { onEvent(BudgetEvent.SaveClicked) },
        onDismiss = { onEvent(BudgetEvent.EditorDismissed) },
        detail = {
            EditorForm(
                editor = editor,
                availableCategories = availableCategories,
                subcategories = subcategories,
                currency = currency,
                onEvent = onEvent,
            )
        },
    )
}

/**
 * The form itself.
 *
 * **Scrollable, and device testing is why.** A real taxonomy has around forty
 * debit categories; as a plain Column they pushed the amount field past the
 * dialog's maximum height with no way to reach it, so the form could be opened
 * and never completed. The cap stops the dialog growing to fill a tall screen;
 * the scroll makes the rest reachable on a short one.
 */
@Composable
private fun EditorForm(
    editor: BudgetEditorState,
    availableCategories: List<Category>,
    subcategories: List<Category>,
    currency: String,
    onEvent: (BudgetEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = EDITOR_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        // **Only the category pair is fixed** (Q20). It is what the budget *is*
        // -- the repository enforces one live budget per category/subcategory
        // -- so changing it is creating a different budget, not editing this
        // one. Everything below was hidden here too until Q20, which is how an
        // accidentally-ticked rollover became a delete-and-rebuild.
        if (!editor.isEdit) {
            LfDetailRow(
                label = "Category",
                value = availableCategories.firstOrNull { it.id == editor.categoryId }?.name,
                placeholder = "Choose one",
                onClick = { onEvent(BudgetEvent.PickerOpened(BudgetPickerField.CATEGORY)) },
            )
            // §5.7's "optionally per-subcategory". Only offered when the picked
            // category has children -- a picker that can only disappoint is
            // worse than no picker.
            if (subcategories.isNotEmpty()) {
                LfDetailRow(
                    label = "Narrow to",
                    value = subcategories.firstOrNull { it.id == editor.subcategoryId }?.name,
                    placeholder = "All of it",
                    onClick = { onEvent(BudgetEvent.PickerOpened(BudgetPickerField.SUBCATEGORY)) },
                )
            }
        }

        PeriodPicker(
            selected = editor.period,
            onPick = { onEvent(BudgetEvent.PeriodPicked(it)) },
        )
        StartDateRow(
            startDate = editor.startDate,
            onClick = { onEvent(BudgetEvent.StartDateClicked) },
        )
        LfSwitchRow(
            label = "Roll over what is left",
            checked = editor.rolloverEnabled,
            onCheckedChange = { onEvent(BudgetEvent.RolloverToggled) },
        )

        LfAmountField(
            value = editor.amountText,
            onValueChange = { onEvent(BudgetEvent.AmountChanged(it)) },
            currencyCode = currency,
            label = "Amount",
        )

        if (editor.error != null) {
            Text(
                text = editor.error,
                style = LfTheme.typography.label,
                color = LfTheme.colors.warn,
            )
        }
    }
}

/**
 * The taxonomy pickers, over the editor.
 *
 * **The app's one picker, not a chip strip.** Both fields shipped as
 * horizontally scrolling `LazyRow`s of chips — which was itself a fix for a
 * `FlowRow` cloud of forty categories that filled the dialog — and both were
 * still a second way of choosing a category in an app whose entry form already
 * had one. A row that opens `LfPickerDialog` costs the same single line as the
 * chip strip and shows the whole list vertically when tapped, with the
 * selection in the accessibility tree rather than only in the palette.
 */
@Composable
private fun BudgetPickers(
    editor: BudgetEditorState,
    availableCategories: List<Category>,
    subcategories: List<Category>,
    onEvent: (BudgetEvent) -> Unit,
) {
    when (editor.openPicker) {
        null -> Unit

        BudgetPickerField.CATEGORY -> LfPickerDialog(
            title = "Category",
            body = "Debit categories without a budget yet.",
            // Top-level only. `observe` returns the taxonomy flat, so the
            // unfiltered list offered subcategories as budget categories --
            // beside a separate control for narrowing to a subcategory, which
            // made the two fields read as the same question asked twice.
            options = availableCategories
                .filterNot { it.isSubcategory }
                .map { LfPickerOption(it.id, it.name) },
            selectedId = editor.categoryId,
            onSelect = { id ->
                if (id != null) onEvent(BudgetEvent.CategoryPicked(id))
                onEvent(BudgetEvent.PickerOpened(null))
            },
            onDismiss = { onEvent(BudgetEvent.PickerOpened(null)) },
            emptyMessage = "Every category already has a budget.",
        )

        BudgetPickerField.SUBCATEGORY -> LfPickerDialog(
            title = "Narrow to",
            body = "Limit the budget to one subcategory, or leave it across the whole category.",
            options = subcategories.map { LfPickerOption(it.id, it.name) },
            selectedId = editor.subcategoryId,
            // Clearing *is* "all of it", which is why the whole-category case
            // needs no option of its own: `LfPickerDialog`'s confirm slot is
            // already the un-choose, and an explicit "All" chip beside it would
            // be two controls for one decision.
            onSelect = { id ->
                onEvent(BudgetEvent.SubcategoryPicked(id))
                onEvent(BudgetEvent.PickerOpened(null))
            },
            onDismiss = { onEvent(BudgetEvent.PickerOpened(null)) },
            emptyMessage = "This category has no subcategories.",
        )
    }
}

/**
 * When the period starts, and therefore when every later period does.
 *
 * §5.7's periods repeat from `start_date` rather than snapping to a calendar,
 * so this is not cosmetic: a monthly budget started on the 10th runs the 10th
 * to the 9th. The row states the chosen date rather than hiding it behind an
 * icon, because the consequence is invisible otherwise.
 */
@Composable
private fun StartDateRow(startDate: Int, onClick: () -> Unit) {
    Text(
        text = "Starts",
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
    LfActionRow(alignment = LfActionAlignment.Start) {
        LfButton(
            text = formatEpochDay(startDate),
            onClick = onClick,
            style = LfButtonStyle.Inline,
        )
    }
}

/**
 * Material's date picker, converted to and from days since epoch.
 *
 * The dialog speaks UTC millis; `local_date` is a day number (§6.1). Converting
 * at the boundary keeps the timezone question in one place rather than letting
 * millis leak into a column that deliberately has no time in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(selected: Int, onPicked: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.toLong() * MILLIS_PER_DAY,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LfButton(
                text = "Set",
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onPicked((millis / MILLIS_PER_DAY).toInt())
                    else onDismiss()
                },
                style = LfButtonStyle.Inline,
            )
        },
        dismissButton = {
            LfButton(text = "Cancel", onClick = onDismiss, style = LfButtonStyle.Inline)
        },
    ) {
        DatePicker(state = state)
    }
}

/** `12 Aug 2026` from a day number. */
private fun formatEpochDay(epochDay: Int): String {
    val date = java.time.LocalDate.ofEpochDay(epochDay.toLong())
    return "${date.dayOfMonth} " +
        date.month.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        ) + " ${date.year}"
}

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
private fun PeriodPicker(selected: BudgetPeriod, onPick: (BudgetPeriod) -> Unit) {
    Text(
        text = "Period",
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
    // Four chips, which do fit on a phone -- `LfActionRow` so they wrap as
    // whole controls at font scale 2.0 rather than clipping (BUG9).
    LfActionRow(alignment = LfActionAlignment.Start) {
        BudgetPeriod.entries.forEach { period ->
            LfChip(
                label = period.label(),
                style = if (period == selected) LfChipStyle.Selected else LfChipStyle.Assist,
                onClick = { onPick(period) },
            )
        }
    }
}

/**
 * Sentence case, not the enum name.
 *
 * "WEEKLY" is a constant; "Weekly" is a word. The mapping is explicit rather
 * than a `lowercase().capitalize()` so a future period with two words does not
 * silently render as "Halfyearly".
 */
private fun BudgetPeriod.label(): String = when (this) {
    BudgetPeriod.WEEKLY -> "Weekly"
    BudgetPeriod.MONTHLY -> "Monthly"
    BudgetPeriod.QUARTERLY -> "Quarterly"
    BudgetPeriod.YEARLY -> "Yearly"
}

/**
 * Tall enough for the picker, short enough to leave the actions on screen.
 *
 * Measured against the device's 360dp-wide, ~780dp-tall viewport: beyond this
 * the dialog's own Cancel/Save row starts being pushed off, which is a worse
 * failure than scrolling.
 */
private val EDITOR_MAX_HEIGHT: Dp = 380.dp
