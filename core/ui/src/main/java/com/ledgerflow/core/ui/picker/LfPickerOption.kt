package com.ledgerflow.core.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme

/** One selectable option: an id to report back, and a label to show. */
@Immutable
public data class LfPickerOption(val id: String, val label: String)

/**
 * The app's one way to choose a category, a merchant, a payment method or a
 * book (SPEC.md §5.4).
 *
 * **Lifted out of `:feature:entry` so the Inbox's review screen is not a second
 * implementation of it.** The owner's requirement is that reviewing a captured
 * message and typing one by hand feel identical; two pickers written separately
 * would drift on the first change to either, and the drift would show up as the
 * two screens disagreeing about how you pick a category. One component, driven
 * by two ViewModels with their own event types, is what keeps that promise
 * mechanically rather than by discipline.
 *
 * It knows no domain type — options are ids and labels the host has already
 * resolved — which is what lets it live here without `:core:ui` learning what a
 * merchant is (CLAUDE.md §3).
 *
 * @param onCreate offered *above* the list when non-null. That order is
 *   deliberate: once someone has typed a name the list does not contain,
 *   creating it is what they came for, and a column of near-misses above the
 *   answer is the wrong thing to read first.
 */
@Composable
public fun LfPickerDialog(
    title: String,
    options: List<LfPickerOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String = "",
    emptyMessage: String = "Nothing to choose from yet.",
    query: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    queryLabel: String = "Search or add",
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
) {
    LfDialog(
        title = title,
        body = body,
        modifier = modifier,
        // "Clear" rather than "OK": tapping a row *is* the selection, so the
        // confirming action is the one thing a row cannot do -- un-choose.
        confirmText = "Clear",
        onConfirm = { onSelect(null) },
        onDismiss = onDismiss,
        detail = {
            Column {
                if (query != null && onQueryChange != null) {
                    LfTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = queryLabel,
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = PICKER_MAX_HEIGHT.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (createLabel != null && onCreate != null) {
                        LfChoiceRow(
                            label = createLabel,
                            selected = false,
                            emphasised = true,
                            onClick = onCreate,
                        )
                    }
                    if (options.isEmpty() && onCreate == null) {
                        Text(
                            text = emptyMessage,
                            style = LfTheme.typography.bodyM,
                            color = LfTheme.colors.textSecondary,
                        )
                    }
                    options.forEach { option ->
                        LfChoiceRow(
                            label = option.label,
                            selected = option.id == selectedId,
                            onClick = { onSelect(option.id) },
                        )
                    }
                }
            }
        },
    )
}

/**
 * One row of a picker.
 *
 * A full-width touch target rather than a radio button and a label: the whole
 * row is the control, and at font scale 2.0 a long category name wraps inside
 * it instead of pushing a control off the edge (BUG9).
 */
@Composable
public fun LfChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Text(
        text = label,
        style = LfTheme.typography.bodyL,
        // `emphasised` is the create row, which is an *action* rather than a
        // choice -- it reads in the accent colour for the same reason a
        // selected row does, because both are the row that does something.
        color = if (selected || emphasised) LfTheme.colors.accent else LfTheme.colors.textPrimary,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.sm),
    )
}

/**
 * A label, the value it currently holds, and a tap to change it.
 *
 * The other half of the entry form's vocabulary, lifted here for the same
 * reason as [LfPickerDialog]: the review screen shows the same rows and must
 * not be a second rendering of them.
 */
@Composable
public fun LfDetailRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "None",
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
            // A row label is a control label (BUG9): whole, on one line. The
            // value beside it is what wraps if anything has to.
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value ?: placeholder,
            style = LfTheme.typography.bodyL,
            color = if (value == null) LfTheme.colors.textTertiary else LfTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Tall enough for a real category list, short enough to leave the dialog a dialog. */
private const val PICKER_MAX_HEIGHT = 320
