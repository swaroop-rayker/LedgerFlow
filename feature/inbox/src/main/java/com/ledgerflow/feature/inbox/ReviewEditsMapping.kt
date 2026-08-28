package com.ledgerflow.feature.inbox

import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.inbox.ReviewEditLine
import com.ledgerflow.core.domain.inbox.ReviewEdits

/**
 * The review screen's state, to and from [ReviewEdits] and back. v8.
 *
 * The *shape* is `:core:domain`'s, because the Inbox and Ledger rows render it
 * too — see [ReviewEdits]. This mapping stays here, because it is about this
 * screen's fields and nothing else consumes those.
 *
 * The amount crosses the boundary twice over: [ReviewEdits.amountText] is what
 * was typed, kept raw so a restored form does not move the caret or rewrite
 * `12.` as `12.00`, and [ReviewEdits.amountMinor] is what that text *means* for
 * the lists. They are written together here rather than one being derived from
 * the other on read, because parsing needs the install's currency and a list
 * row has no business knowing about `MoneyFormat`.
 */
internal fun ReviewUiState.toEdits(currency: String): ReviewEdits = ReviewEdits(
    ledger = ledger,
    amountText = amountText,
    // `parse` answers 0 for blank or unparseable text, and the approval path
    // already treats <= 0 as "no amount yet". Null here means the same thing to
    // a list, which would otherwise render a confident ₹0.00 over a figure the
    // message actually stated.
    amountMinor = MoneyFormat.parse(amountText, currency).takeIf { it > 0L },
    occurredAt = occurredAt,
    noteText = noteText,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    itemised = itemised,
    lines = lines.map { line ->
        ReviewEditLine(
            key = line.key,
            name = line.name,
            unitPriceText = line.unitPriceText,
            unitPriceMinor = line.unitPriceMinor,
            quantityText = line.quantityText,
            quantityMilli = line.quantityMilli,
            categoryId = line.categoryId,
            subcategoryId = line.subcategoryId,
        )
    },
)

/**
 * Saved edits, back over the state the extraction produced.
 *
 * Applied **after** the candidate's own `toUiState` rather than instead of it,
 * so everything the edits do not carry — the source label, the reference hint,
 * `needsManualFill`, the raw merchant name — still comes from the message.
 * Those are facts about what arrived, not things the user typed, and an edit
 * has no business overriding them.
 */
internal fun ReviewUiState.withEdits(edits: ReviewEdits): ReviewUiState = copy(
    ledger = edits.ledger ?: ledger,
    // The book control shows only while the parser could not read a direction.
    // An edit that supplied one does not make the message any clearer, so the
    // row stays -- otherwise the user could pick a book, leave, come back, and
    // find the control gone.
    bookIsUnread = bookIsUnread,
    amountText = edits.amountText,
    occurredAt = edits.occurredAt ?: occurredAt,
    noteText = edits.noteText,
    categoryId = edits.categoryId,
    subcategoryId = edits.subcategoryId,
    merchantId = edits.merchantId,
    paymentMethodId = edits.paymentMethodId,
    itemised = edits.itemised,
    lines = edits.lines.map { line ->
        ReviewLine(
            key = line.key,
            name = line.name,
            unitPriceText = line.unitPriceText,
            unitPriceMinor = line.unitPriceMinor,
            quantityText = line.quantityText,
            quantityMilli = line.quantityMilli,
            categoryId = line.categoryId,
            subcategoryId = line.subcategoryId,
        )
    },
)
