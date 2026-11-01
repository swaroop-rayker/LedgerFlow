package com.ledgerflow.core.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.ui.LfFormRowTokens

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
                        .heightIn(max = LfLayout.dialogListMax)
                        .verticalScroll(rememberScrollState())
                        // One choice among these rows, to TalkBack (BUG83).
                        .selectableGroup(),
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
                            style = LfFormRowTokens.supportingStyle,
                            color = LfFormRowTokens.secondary,
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
 *
 * **The chosen row is marked by a fill and a tick, not by colour** (BUG83).
 * It used to be drawn in `action.primary`, which under Night Vault is the same
 * colour as every other row's text, so the merchant, category or payment
 * method already chosen looked unchosen. TalkBack hears it too: the row is
 * `selectable` with a radio button's role, so it reads "selected".
 *
 * @param emphasised the create row: an action rather than a choice, so it
 *   leads with a + and is a button to TalkBack, never "selected".
 */
@Composable
public fun LfChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val inset = LfFormRowTokens.choiceInset
    val action = if (emphasised) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bleed(inset)
            .clip(LfFormRowTokens.selectedShape)
            .then(if (selected) Modifier.background(LfFormRowTokens.selectedFill) else Modifier)
            .then(action)
            .defaultMinSize(minHeight = LfFormRowTokens.minHeight)
            .padding(horizontal = inset, vertical = LfFormRowTokens.paddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfFormRowTokens.gap),
    ) {
        if (emphasised) {
            Icon(imageVector = LfIcons.Add, contentDescription = null, tint = LfFormRowTokens.glyph)
        }
        Text(
            text = label,
            style = LfFormRowTokens.textStyle,
            color = LfFormRowTokens.text,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(imageVector = LfIcons.Selected, contentDescription = null, tint = LfFormRowTokens.glyph)
        }
    }
}

/**
 * Lays the row out [by] wider on each side than its parent gave it, centred,
 * while reporting the parent's width. The fill then reaches into the dialog's
 * padding and the label lines up with the title above it (BUG83).
 */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val wider = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity,
    )
    val placeable = measurable.measure(wider)
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
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
            .defaultMinSize(minHeight = LfFormRowTokens.minHeight)
            .padding(vertical = LfFormRowTokens.paddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfFormRowTokens.labelGap),
    ) {
        Text(
            text = label,
            style = LfFormRowTokens.supportingStyle,
            color = LfFormRowTokens.secondary,
            // A row label is a control label (BUG9): whole, on one line. The
            // value beside it is what wraps if anything has to.
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value ?: placeholder,
            style = LfFormRowTokens.textStyle,
            color = if (value == null) LfFormRowTokens.muted else LfFormRowTokens.text,
            modifier = Modifier.weight(1f),
        )
    }
}

