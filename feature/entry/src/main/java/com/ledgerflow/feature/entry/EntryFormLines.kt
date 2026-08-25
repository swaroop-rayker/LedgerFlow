package com.ledgerflow.feature.entry

import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.QuantityFormat
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.core.ui.lineitem.LineItemEditorState
import com.ledgerflow.core.ui.lineitem.LineItemRow

/*
 * The itemised half of the entry form (SPEC.md §5.4, ADR-0018).
 *
 * Pure transforms over `Form`, and the mappings out of it. Split from
 * `EntryViewModel.kt` when that file crossed detekt's function ceiling -- the
 * limit was measuring something real. None of this needs an instance, a
 * form-to-form mapping is not a responsibility of the object that owns the
 * form's lifetime, and the file had become two subjects sharing a name.
 *
 * `internal` rather than `private` only because these now live beside the class
 * that uses them instead of inside its file. None of it is part of the
 * feature's API.
 */

/**
 * ADR-0018: an itemised entry files at line grain and nowhere else.
 *
 * The entry's own category is not merely unused in itemised mode -- it is not
 * written. A ledger row that kept one would be asserting a single category for
 * a bill that has three, and every reader downstream would believe it.
 */
internal fun Form.entryAssignment() = EntryAssignment(
    categoryId = if (itemised) null else categoryId,
    subcategoryId = if (itemised) null else subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
)

internal fun toNewLine(line: EntryLineItem) = NewLineItem(
    name = line.name,
    // The product, computed once by the form and sent as-is. The unit price and
    // quantity travel with it so the stored row can explain where the figure
    // came from.
    total = Money(line.amountMinor),
    quantityMilli = line.quantityMilli,
    unitPrice = Money(line.unitPriceMinor),
    categoryId = line.categoryId,
    subcategoryId = line.subcategoryId,
)

/**
 * Switches how this entry is filed (ADR-0018).
 *
 * Entering itemised mode moves the entry's category *down* onto the first line
 * rather than discarding it: the user has already answered "what is this", and
 * the answer is still true of at least part of the bill.
 *
 * Leaving is the destructive direction, so it raises a confirmation instead of
 * acting -- but only when there is something to lose. A dialog over an empty
 * editor is a dialog about nothing, which is how people learn to dismiss
 * dialogs unread.
 */
internal fun Form.withMode(itemised: Boolean, newKey: String): Form = when {
    itemised == this.itemised -> this
    itemised -> asItemised(newKey)
    lineItems.any { it.hasContent } -> copy(confirmingSingleItem = true)
    else -> asSingleItem()
}

/**
 * Into itemised mode (ADR-0018).
 *
 * The entry's category moves *down* onto the first line rather than being
 * discarded: the user has already answered "what is this", and the answer is
 * still true of at least part of the bill. The entry then files nothing of its
 * own, which is cleared here rather than at save so the form shows the truth
 * about what will be written.
 *
 * Pure, and takes the new key as an argument rather than a generator, for the
 * reason the other transforms below are top-level: a form-to-form mapping is
 * not a responsibility of the object that owns the form's lifetime.
 */
internal fun Form.asItemised(newKey: String): Form {
    val seeded = lineItems.ifEmpty {
        listOf(EntryLineItem(key = newKey, categoryId = categoryId, subcategoryId = subcategoryId))
    }
    return copy(
        itemised = true,
        categoryId = null,
        subcategoryId = null,
        lineItems = seeded,
        expandedLineKey = seeded.firstOrNull()?.key,
    )
}

/** Back to a single item. Destructive, so the caller confirms first. */
internal fun Form.asSingleItem(): Form = copy(
    itemised = false,
    lineItems = emptyList(),
    expandedLineKey = null,
    confirmingSingleItem = false,
)

/**
 * Adds a line, pre-filed like the one above it.
 *
 * A twelve-line grocery bill is mostly one category with two exceptions, so
 * inheriting turns twelve category picks into two. The first line inherits from
 * the entry instead, which is what makes switching into itemised mode lossless.
 *
 * The new row opens, because the user asked for a line in order to type in it.
 */
internal fun Form.withNewLine(newKey: String): Form {
    val previous = lineItems.lastOrNull()
    val line = EntryLineItem(
        key = newKey,
        categoryId = previous?.categoryId ?: categoryId,
        subcategoryId = previous?.subcategoryId ?: subcategoryId,
    )
    return copy(lineItems = lineItems + line, expandedLineKey = line.key)
}

/**
 * Opens a line's subcategory picker, parented on that line's category.
 *
 * Unchanged when the line has no category yet: §6.1.1's invariant is that a
 * subcategory's parent *is* the category, so there would be no list to show.
 * The editor does not render the row in that state either, which makes this a
 * guard rather than a reachable path.
 */
internal fun Form.lineSubcategoryPicker(key: String): Form {
    val parentId = lineItems.firstOrNull { it.key == key }?.categoryId ?: return this
    return copy(picker = EntryPicker.Subcategory(parentId = parentId, lineKey = key))
}

/**
 * A repeat-expense chip's assignment, applied wholesale (§5.4).
 *
 * All four fields together rather than field by field: the chip's value is that
 * it is a *combination* the user has filed before, and applying half of one
 * would produce a pairing they never chose.
 */
internal fun Form.filedAs(combo: EntryComboChip): Form = copy(
    categoryId = combo.categoryId,
    subcategoryId = combo.subcategoryId,
    merchantId = combo.merchantId,
    paymentMethodId = combo.paymentMethodId,
)

/**
 * Whether a line is worth saving.
 *
 * A row the user added and never typed in is not an item -- it is an empty
 * form. Saving it would mean a blank name reaching the approval, which refuses
 * it (`LineItemNameBlank`), so an untouched row would make the Save button fail
 * for a line nobody meant to create.
 */
internal val EntryLineItem.hasContent: Boolean
    get() = name.isNotBlank() || unitPriceMinor != 0L || categoryId != null

/**
 * The editor's view of the lines: names resolved, amounts formatted.
 *
 * Done here rather than in the composable because it needs the taxonomy and the
 * base currency, and a stateless composable has no business looking either up
 * (CLAUDE.md §5). `:core:ui` therefore renders strings and knows no domain type,
 * which is what lets the Inbox reuse it at P2.
 */
internal fun Form.editorState(
    categoryNames: Map<String, String>,
    currencyCode: String,
): LineItemEditorState {
    if (!itemised) return LineItemEditorState()

    val remainder = amountMinor - lineItems.sumOf { it.amountMinor }

    return LineItemEditorState(
        rows = lineItems.map { line ->
            LineItemRow(
                key = line.key,
                name = line.name,
                unitPriceText = line.unitPriceText,
                quantityText = line.quantityText,
                totalText = MoneyFormat.symbolised(line.amountMinor, currencyCode),
                // Null at one. "×1" on every row of a grocery list is a column
                // of noise on the one line a collapsed row has to work with.
                quantityLabel = line.quantityMilli
                    .takeIf { it != Quantity.SCALE }
                    ?.let { "×" + QuantityFormat.plain(it) },
                categoryName = line.categoryId?.let(categoryNames::get),
                subcategoryName = line.subcategoryId?.let(categoryNames::get),
            )
        },
        expandedKey = expandedLineKey,
        summary = allocationSummary(remainder, currencyCode),
        balanced = remainder == 0L,
    )
}

/**
 * What is still unaccounted for: "₹160 left".
 *
 * The delta is shown rather than corrected: §5.4 allows saving an unbalanced
 * set and the approval records the difference as an `UNALLOCATED` line, so
 * hiding it here would make that row appear from nowhere.
 */
internal fun Form.allocationSummary(remainder: Long, currencyCode: String): String? = when {
    lineItems.none { it.hasContent } -> null
    remainder == 0L -> "All allocated"
    // The remainder alone, not "₹600 of ₹1,000 · ₹400 left". Measured on
    // device at the user's font scale: the long form used the header's entire
    // width beside the "Items" label, and two of its three figures are already
    // on screen -- the entry total is directly above and the running total is
    // the sum of the rows below. What is left is the only number the reader
    // cannot work out by looking.
    remainder > 0L -> MoneyFormat.symbolised(remainder, currencyCode) + " left"
    else -> MoneyFormat.symbolised(-remainder, currencyCode) + " over"
}

/**
 * A stored line, back into the form.
 *
 * `unitPriceMinor` falls back to the legacy `amountMinor` for a draft written
 * before ADR-0018, whose lines carried a total and no unit price. At quantity
 * one those are the same number, which is exactly what such a line meant.
 */
internal fun DraftLineItem.toLine(currencyCode: String): EntryLineItem {
    val unitPrice = if (unitPriceMinor != 0L) unitPriceMinor else amountMinor
    val quantity = if (quantityMilli > 0L) quantityMilli else Quantity.SCALE
    return EntryLineItem(
        key = key,
        name = name,
        // Re-derived from the value, exactly as the entry amount is: a draft
        // survives a change to how numbers are typed.
        unitPriceText = unitPrice.asAmountText(currencyCode),
        unitPriceMinor = unitPrice,
        quantityText = if (quantity == Quantity.SCALE) "" else QuantityFormat.plain(quantity),
        quantityMilli = quantity,
        categoryId = categoryId,
        subcategoryId = subcategoryId,
    )
}
