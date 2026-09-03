package com.ledgerflow.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Category

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
    currency: String,
    onEvent: (BudgetEvent) -> Unit,
) {
    LfDialog(
        title = if (editor.isEdit) "Edit budget" else "New budget",
        body = if (editor.isEdit) {
            "Change how much ${editor.categoryName} may cost each period."
        } else {
            "Pick a category and set a limit for the period."
        },
        confirmText = "Save",
        onConfirm = { onEvent(BudgetEvent.SaveClicked) },
        onDismiss = { onEvent(BudgetEvent.EditorDismissed) },
        detail = {
            // **Scrollable, and device testing is why.** A real taxonomy has a
            // dozen debit categories; as a plain Column the chip cloud pushed
            // the amount field past the dialog's maximum height with no way to
            // reach it, so a budget could be started and never finished. The
            // cap keeps the dialog from growing to fill the screen on a tall
            // device, and the scroll makes the rest reachable on a short one.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = EDITOR_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                if (!editor.isEdit) {
                    CategoryPicker(
                        categories = availableCategories,
                        selectedId = editor.categoryId,
                        onPick = { onEvent(BudgetEvent.CategoryPicked(it)) },
                    )
                    PeriodPicker(
                        selected = editor.period,
                        onPick = { onEvent(BudgetEvent.PeriodPicked(it)) },
                    )
                }

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
        },
    )
}

@Composable
private fun CategoryPicker(
    categories: List<Category>,
    selectedId: String?,
    onPick: (String) -> Unit,
) {
    Text(
        text = "Category",
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
    // **One scrolling row, not a wrapping cloud** — and the device is what
    // decided it. The seeded taxonomy has around forty debit categories; as a
    // `FlowRow` they filled the whole dialog and pushed Period and Amount out
    // of reach, so the form could be opened and never completed. A single row
    // costs one line whatever the category count, which puts the field the user
    // came to fill immediately below it. Same shape as the Analytics range
    // chips, for the same reason.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        items(categories, key = { it.id }) { category ->
            LfChip(
                label = category.name,
                style = if (category.id == selectedId) {
                    LfChipStyle.Selected
                } else {
                    LfChipStyle.Assist
                },
                onClick = { onPick(category.id) },
            )
        }
    }
}

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
