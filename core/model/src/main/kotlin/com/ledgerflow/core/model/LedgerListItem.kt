package com.ledgerflow.core.model

/**
 * One row of the Ledger list (SPEC.md §5.5, §9.3).
 *
 * Deliberately **not** [LedgerEntry]. A full entry carries its line items, its
 * foreign-currency trio, its provenance and its recurrence flag; a list row
 * needs none of that, and loading it would mean a second query per row for
 * items nobody is looking at. This is the projection the list actually renders,
 * and it is what the paged query returns.
 *
 * [ledger] is carried on the row even though the whole page comes from one
 * book, because it is what the screen reads to colour the amount and to choose
 * its `-`/`+` prefix. **There is no sign here to infer from:** [amount] is
 * always positive, exactly as it is in `ledger_entry` (Law 2 — a negative
 * credit would be a figure that nets against a debit). The prefix is produced
 * at draw time by `MoneyFormat.directional` from this field; it is never
 * stored, parsed back, or summed.
 *
 * [categoryName] and [merchantName] are resolved in the query rather than by
 * the caller, so a page is one read. They are nullable because an entry may be
 * filed under neither -- an unparsed ingest row lands with `confidence = 0` and
 * no assignment at all (§5.1) -- and because the join deliberately does **not**
 * filter soft-deleted taxonomy rows: a hidden merchant keeps labelling the
 * entries it was on (§5.5), which is the whole reason merchants soft-delete.
 *
 * [lineItemCategoryName] is the second, and rarer, reason [categoryName] can be
 * null: an itemised entry stores no category of its own (ADR-0018), so its
 * filing lives entirely on its line items. Reading [categoryName] alone cannot
 * tell "genuinely uncategorised" apart from "categorised three ways at line
 * grain" -- both come back null -- which is why this row carries both and a
 * screen reads [displayCategoryName] rather than [categoryName] directly.
 */
public data class LedgerListItem(
    val id: String,
    val ledger: LedgerType,
    /** Positive, base currency, minor units (Law 3). */
    val amount: Money,
    /** ISO-4217. Equal to `app_meta.baseCurrency` in v1 (§5.8). */
    val currency: String,
    val occurredAt: Long,
    /** Days since epoch, device tz at capture. What the day headers group on. */
    val localDate: Int,
    val categoryName: String?,
    /** ARGB of the category's swatch. Null exactly when [categoryName] is. */
    val categoryColorArgb: Int?,
    val merchantName: String?,
    val note: String?,
    /**
     * The line item with the largest signed total, among this entry's
     * categorised lines (ADR-0018). Null whenever [categoryName] is non-null
     * (a plain entry) or every line item is uncategorised.
     */
    val lineItemCategoryName: String? = null,
    /** The swatch for [lineItemCategoryName]. Null exactly when it is. */
    val lineItemCategoryColorArgb: Int? = null,
    /** Distinct categories among this entry's line items. 0 when none. */
    val lineItemCategoryCount: Int = 0,
) {
    /**
     * What a row shows as its category: the entry's own if it has one, else
     * its line items' largest one (ADR-0018). Null only when neither exists.
     */
    val displayCategoryName: String? get() = categoryName ?: lineItemCategoryName

    /** The swatch to go with [displayCategoryName]. Null exactly when it is. */
    val displayCategoryColorArgb: Int? get() = categoryColorArgb ?: lineItemCategoryColorArgb

    /**
     * "+2" worth of further categories on an itemised row, or null when there
     * is nothing more to say -- a plain entry, or an itemised one that turned
     * out to be all one category.
     *
     * Gated on [categoryName] rather than [lineItemCategoryName]: the count is
     * only ever about *line-item* categories, and a plain entry's
     * [lineItemCategoryCount] is always 0 regardless, so this reads correctly
     * without repeating that check.
     */
    val additionalCategoryCount: Int?
        get() = (lineItemCategoryCount - 1).takeIf { categoryName == null && it > 0 }
}
