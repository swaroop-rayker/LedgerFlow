package com.ledgerflow.core.ui.lineitem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The line-item editor (SPEC.md §5.4, ADR-0018).
 *
 * One payment at a shop that sells across categories is not one category of
 * spend, and this is where the user says so: each line carries its own name,
 * unit price, quantity and filing, and an itemised entry files nowhere else.
 *
 * ## Why it is shaped like this
 *
 * **One card, one divider per line, one row expanded at a time.** A grocery
 * bill runs to a dozen lines. The previous editor spent a whole card and five
 * lines of height per item, which is unreadable at that length and breaks the
 * compact brief in CLAUDE.md §5 twice over — a list exists to be scanned, and
 * in-card actions are `Inline` rather than pill buttons. Collapsed rows are two
 * lines: what it is and what it cost, then how it is filed.
 *
 * **Two lines rather than one.** Squeezing name, quantity, category and total
 * onto a single line survives font scale 1.0 and collapses at 2.0, where
 * something has to give and the only candidates are a clipped label (BUG9) or a
 * crushed amount. Splitting them means the name ellipsises — it is a value, not
 * a label — while the amount stays whole at any scale.
 *
 * Stateless throughout: which row is open comes in as
 * [LineItemEditorState.expandedKey] and every interaction leaves as a
 * [LineItemEditorEvent].
 */
@Composable
public fun LfLineItemEditor(
    state: LineItemEditorState,
    onEvent: (LineItemEditorEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        Header(state)

        LfCard {
            Column {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) LfDivider()
                    if (row.key == state.expandedKey) {
                        ExpandedRow(row, onEvent)
                    } else {
                        CollapsedRow(row, onEvent)
                    }
                }
                if (state.rows.isNotEmpty()) LfDivider()
                LfActionRow(alignment = LfActionAlignment.Start) {
                    LfButton(
                        text = "Add item",
                        style = LfButtonStyle.Inline,
                        onClick = { onEvent(LineItemEditorEvent.AddRequested) },
                    )
                }
            }
        }
    }
}

/**
 * "Items" and the running reconciliation.
 *
 * The delta is shown rather than corrected. §5.4 allows saving an unbalanced
 * set and the approval records the difference as an `UNALLOCATED` line, so
 * hiding it here would make that row appear from nowhere.
 */
@Composable
private fun Header(state: LineItemEditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Items",
            style = LfTheme.typography.label,
            color = LfTheme.colors.textSecondary,
            // A section heading is a label (BUG9): whole, on one line.
            maxLines = 1,
            softWrap = false,
        )
        state.summary?.let { summary ->
            Text(
                text = summary,
                style = LfTheme.typography.bodyM,
                color = if (state.balanced) LfTheme.colors.textSecondary else LfTheme.colors.warn,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * A line at rest: what it is and what it cost, then how it is filed.
 *
 * The whole row opens it, rather than a chevron — the target is the row, and at
 * two lines of height it is already comfortably past the minimum.
 */
@Composable
private fun CollapsedRow(row: LineItemRow, onEvent: (LineItemEditorEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(LineItemEditorEvent.Expanded(row.key)) }
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.name.ifBlank { "Untitled item" },
                style = LfTheme.typography.bodyL,
                color = if (row.name.isBlank()) {
                    LfTheme.colors.textTertiary
                } else {
                    LfTheme.colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.totalText,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
                // The one thing on this row that must never be abbreviated: a
                // truncated amount is worse than no amount.
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = listOfNotNull(row.filedAs ?: "No category", row.quantityLabel)
                .joinToString("  ·  "),
            style = LfTheme.typography.bodyM,
            color = if (row.filedAs == null) {
                LfTheme.colors.textTertiary
            } else {
                LfTheme.colors.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The one line being edited. */
@Composable
private fun ExpandedRow(row: LineItemRow, onEvent: (LineItemEditorEvent) -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = LfTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        LfTextField(
            value = row.name,
            onValueChange = { onEvent(LineItemEditorEvent.NameChanged(row.key, it)) },
            label = "Item",
        )

        // Price and quantity share a line: they are one thought, and the line
        // total below is the sentence they make. Stacked, the expanded row
        // grows past a screenful at large font scales for no gain.
        Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            LfTextField(
                value = row.unitPriceText,
                onValueChange = { onEvent(LineItemEditorEvent.UnitPriceChanged(row.key, it)) },
                label = "Unit price",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(UNIT_PRICE_WEIGHT),
            )
            LfTextField(
                value = row.quantityText,
                onValueChange = { onEvent(LineItemEditorEvent.QuantityChanged(row.key, it)) },
                label = "Qty",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(QUANTITY_WEIGHT),
            )
        }

        // Read-only, and shown rather than editable: it is the product of the
        // two fields above, and a third editable figure would be a third thing
        // that can disagree with them.
        FilingRow(label = "Line total", value = row.totalText, onClick = null)

        FilingRow(
            label = "Category",
            value = row.categoryName,
            onClick = { onEvent(LineItemEditorEvent.CategoryRequested(row.key)) },
        )
        if (row.categoryName != null) {
            FilingRow(
                label = "Subcategory",
                value = row.subcategoryName,
                onClick = { onEvent(LineItemEditorEvent.SubcategoryRequested(row.key)) },
            )
        }

        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = "Remove",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(LineItemEditorEvent.RemoveRequested(row.key)) },
            )
            LfButton(
                text = "Done",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(LineItemEditorEvent.Collapsed) },
            )
        }
    }
}

/**
 * A label and its value, tappable when there is somewhere to go.
 *
 * The same shape as the entry form's own detail rows, so a line's category is
 * chosen exactly the way the entry's is — one picker, one interaction, learned
 * once.
 */
@Composable
private fun FilingRow(label: String, value: String?, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value ?: "None",
            style = LfTheme.typography.bodyM,
            color = if (value == null) LfTheme.colors.textTertiary else LfTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * Price gets the wider share: it holds an amount with a separator and decimals,
 * while a quantity is almost always one or two digits.
 */
private const val UNIT_PRICE_WEIGHT = 1.7f
private const val QUANTITY_WEIGHT = 1f
