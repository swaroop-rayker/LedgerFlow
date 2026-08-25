package com.ledgerflow.core.model

/**
 * A soft-deleted entry, as the bin lists it (SPEC.md §5.5).
 *
 * Distinct from [LedgerListItem] because the two answer different questions.
 * A list row helps you read a book; this helps you decide whether to keep
 * something you already threw away — so it carries the subcategory the list
 * omits, and it carries [ledger] as something the row must *show* rather than
 * something the screen already knows.
 *
 * **[ledger] is load-bearing here.** The bin lists both books together, so
 * unlike every other ledger surface a row cannot infer its direction from the
 * screen it is on. It is what colours the amount and picks its sign, and it is
 * what every write about this entry has to be told — an id carries no book
 * inside it, and restoring or purging through the wrong one must affect nothing
 * (Law 2).
 *
 * [amount] is positive, as it is everywhere. Nothing here is ever summed with
 * anything else.
 *
 * [lineItemCategoryName] carries an itemised entry's filing, which lives on its
 * line items rather than on the entry (ADR-0018) — read
 * [displayCategoryName] rather than [categoryName] to render a row.
 */
public data class DeletedEntry(
    val id: String,
    val ledger: LedgerType,
    /** Positive, base currency, minor units (Law 3). */
    val amount: Money,
    /** ISO-4217. Equal to `app_meta.baseCurrency` in v1 (§5.8). */
    val currency: String,
    /** When the entry happened — what the bin sorts and dates rows by. */
    val occurredAt: Long,
    /** When it was binned. Never null: an entry without this is not in the bin. */
    val deletedAt: Long,
    val categoryName: String?,
    /** ARGB of the category's swatch. Null exactly when [categoryName] is. */
    val categoryColorArgb: Int?,
    /**
     * The subcategory, which the Ledger's own rows do not show.
     *
     * It earns its place here: the bin is where a user distinguishes two
     * otherwise-identical entries to decide which one to keep, and the
     * subcategory is often the only thing that separates them.
     */
    val subcategoryName: String?,
    val merchantName: String?,
    val note: String?,
    /**
     * The categorised line with the largest signed total (ADR-0018). Null when
     * [categoryName] is non-null, or when no line item carries a category.
     */
    val lineItemCategoryName: String? = null,
    /** The swatch for [lineItemCategoryName]. Null exactly when it is. */
    val lineItemCategoryColorArgb: Int? = null,
    /** Distinct categories across this entry's line items. 0 when none. */
    val lineItemCategoryCount: Int = 0,
) {
    /**
     * What a row shows as its category: the entry's own if it has one, else its
     * line items' largest (ADR-0018). Null only when neither exists.
     */
    public val displayCategoryName: String? get() = categoryName ?: lineItemCategoryName

    /** The swatch to go with [displayCategoryName]. Null exactly when it is. */
    public val displayCategoryColorArgb: Int? get() = categoryColorArgb ?: lineItemCategoryColorArgb

    /**
     * "+2" worth of further line-item categories, or null when there is nothing
     * more to say.
     *
     * **[subcategoryName] deliberately gets no line-grain equivalent.** It is
     * null on an itemised entry and stays that way: a subcategory belongs to
     * one line, and pairing it with a count that is about *categories* would
     * read as "Food & Dining · Dairy +1" where the "+1" appears to qualify the
     * subcategory. The count is the more useful fact for a bill spanning
     * several categories, and the two cannot share a line without lying about
     * one of them.
     */
    public val additionalCategoryCount: Int?
        get() = (lineItemCategoryCount - 1).takeIf { categoryName == null && it > 0 }
}
