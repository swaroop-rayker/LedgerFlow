package com.ledgerflow.core.domain.inbox

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * What the user has typed on the review screen but not yet approved. v8.
 *
 * **Typed, and in `:core:domain`, because more than one screen renders it.**
 * It started as a `:feature:inbox` payload on the reasoning that form state
 * belongs to the screen that produces it — the split SPEC.md §6.1.2 draws for
 * `draft_entry.payload_json`. That was wrong here, and the symptom was exact:
 * a user corrected a candidate's amount, went back, and the Inbox and Ledger
 * rows still showed the parser's figure. Nothing but the review screen could
 * read the correction, so from outside the app looked like it had not saved at
 * all.
 *
 * A draft's payload really does belong to one screen; a *candidate's* does not,
 * because a candidate is a row that other surfaces list. The distinction is
 * whether anything else draws it.
 *
 * **[amountText] and the lines' text are raw, exactly as typed.** Parsing to
 * [Money] and formatting back moves the caret, and a restored form that had
 * quietly rewritten `12.` as `12.00` has edited the user's input while they
 * were away. [amountMinor] carries what that text *means* for the layers that
 * need a figure rather than a caret — the two are written together and neither
 * is derived from the other at read time.
 */
public data class ReviewEdits(
    val ledger: LedgerType? = null,
    val amountText: String = "",
    /** [amountText] parsed, or null when it does not yet parse to anything. */
    val amountMinor: Long? = null,
    val occurredAt: Long? = null,
    val noteText: String = "",
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
    val itemised: Boolean = false,
    val lines: List<ReviewEditLine> = emptyList(),
) {
    /** The edited amount as money, when the text parsed to one. */
    public val amount: Money? get() = amountMinor?.let(::Money)
}

/** One itemised line, as typed. */
public data class ReviewEditLine(
    val key: String = "",
    val name: String = "",
    val unitPriceText: String = "",
    val unitPriceMinor: Long = 0L,
    val quantityText: String = "",
    val quantityMilli: Long = 0L,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
)
