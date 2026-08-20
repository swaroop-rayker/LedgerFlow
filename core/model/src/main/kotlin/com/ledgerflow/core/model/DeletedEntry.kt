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
)
