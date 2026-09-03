package com.ledgerflow.core.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * [LfPickerDialog]'s multi-select twin, for the analytics filters (§5.6).
 *
 * **Deliberately the same dialog, the same rows and the same scroll**, because
 * the owner's requirement is that choosing a category feel identical wherever
 * it happens. The filters were a horizontally scrolling row of chips instead —
 * a second idiom for the same job, and one that hides most of a forty-category
 * taxonomy off the right-hand edge with nothing to say how much is there. A
 * vertical list of full-width rows shows the same list the entry form shows.
 *
 * The only real difference is arity, and it earns a checkbox rather than a
 * colour: a chip that is "selected" only by its palette tells a screen reader
 * nothing (§9.6), and with several selected at once the reader needs to be able
 * to enumerate them.
 *
 * @param onClear an **inline** action above the list, not the confirm button.
 *   [LfPickerDialog] can put "Clear" on confirm because it closes on the tap
 *   that selects; a multi-select dialog stays open, so its confirm slot is
 *   "Done" and clearing would otherwise take the filled button — which is the
 *   one a user reaches for to leave, and it would wipe their selection. Same
 *   reasoning, and the same shape, as the filter sheet's own "Clear all".
 */
@Composable
public fun LfMultiPickerDialog(
    title: String,
    options: List<LfPickerOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String = "",
    emptyMessage: String = "Nothing to choose from yet.",
    query: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    queryLabel: String = "Search",
) {
    LfDialog(
        title = title,
        body = body,
        modifier = modifier,
        confirmText = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissText = null,
        detail = {
            Column {
                if (selectedIds.isNotEmpty()) {
                    LfActionRow(alignment = LfActionAlignment.Start) {
                        LfButton(
                            text = "Clear (" + selectedIds.size + ")",
                            onClick = onClear,
                            style = LfButtonStyle.Inline,
                        )
                    }
                }
                if (query != null && onQueryChange != null) {
                    LfTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = queryLabel,
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = MULTI_PICKER_MAX_HEIGHT.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (options.isEmpty()) {
                        Text(
                            text = emptyMessage,
                            style = LfTheme.typography.bodyM,
                            color = LfTheme.colors.textSecondary,
                        )
                    }
                    options.forEach { option ->
                        LfCheckRow(
                            label = option.label,
                            checked = option.id in selectedIds,
                            onCheckedChange = { onToggle(option.id) },
                        )
                    }
                }
            }
        },
    )
}

/**
 * One multi-select row: the whole row is the control, the checkbox is the state.
 *
 * `toggleable` on the row rather than a handler on the checkbox, for the reason
 * `LfSwitchRow` records — it makes the label the target *and* merges the
 * descendants, so the checkbox announces itself with the name beside it instead
 * of as an unlabelled control.
 */
@Composable
public fun LfCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
            )
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What a multi-select field shows when it is closed.
 *
 * Names the choice while there is one to name, and counts once naming them
 * would be a paragraph. "All" rather than "None" for the empty case, because an
 * empty filter includes everything — "None" would read as the opposite of what
 * it does.
 */
public fun summariseSelection(
    selectedIds: Set<String>,
    options: List<LfPickerOption>,
    allLabel: String = "All",
): String = when {
    selectedIds.isEmpty() -> allLabel
    selectedIds.size == 1 ->
        options.firstOrNull { it.id in selectedIds }?.label ?: "1 selected"
    else -> "${selectedIds.size} selected"
}

/** Matches [LfPickerDialog]'s list height, so the two dialogs are one size. */
private const val MULTI_PICKER_MAX_HEIGHT = 320
