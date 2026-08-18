package com.ledgerflow.core.model

/**
 * A committed ledger row (SPEC.md §6.1).
 *
 * Reaching this type means a human approved it: nothing constructs a
 * [LedgerEntry] except `ApproveTransactionUseCase`, which is the only writer
 * `ledger_entry` has (Law 1).
 *
 * [amount] is always positive and always in the install's base currency (§5.8).
 * Direction is carried by [ledger], never by a sign -- a negative amount in a
 * credit row would be a figure that nets against a debit somewhere, which is
 * precisely what Law 2 forbids.
 */
public data class LedgerEntry(
    val id: String,
    val ledger: LedgerType,
    val amount: Money,
    /** ISO-4217. Equal to `app_meta.baseCurrency` in v1 (§5.8). */
    val currency: String,
    val occurredAt: Long,
    /** Days since epoch in the capture device's timezone. Derived, never entered. */
    val localDate: Int,
    val assignment: EntryAssignment,
    val note: String?,
    val origin: EntryOrigin,
    /** Present only for spend the user paid in another currency (§5.8). */
    val foreign: ForeignAmount?,
    val isRecurring: Boolean,
    val lineItems: List<LineItem>,
)

/**
 * What an entry is filed under.
 *
 * Grouped rather than spread across the entry's constructor because the four
 * travel together everywhere -- the form edits them as a unit, the approval
 * validates them as a unit, and §6.1.1's invariant is a statement about two of
 * them jointly rather than about either alone.
 */
public data class EntryAssignment(
    val categoryId: String? = null,
    /**
     * Denormalised alongside [categoryId] so analytics group without a
     * self-join. **Its parent must equal [categoryId]** -- a SQLite CHECK
     * cannot hold a subquery, so the approval path is the enforcement point
     * (§6.1.1).
     */
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
)

/**
 * Where an entry came from, for audit only.
 *
 * Never branched on outside an ingest adapter: everything downstream of a
 * capture adapter is source-agnostic (CLAUDE.md §0).
 */
public data class EntryOrigin(
    val source: EntrySource,
    /** `pending_transaction.id` for ingested entries; null for manual (§5.4). */
    val refId: String? = null,
) {
    public companion object {
        /**
         * Manual entry, which does **not** route through `pending_transaction`
         * (§5.4). Law 1 exists so automated sources cannot commit without a
         * human; the Save tap on a form the human just filled in is that human.
         */
        public val Manual: EntryOrigin = EntryOrigin(EntrySource.MANUAL, refId = null)
    }
}

/**
 * Foreign spend, captured by hand (§5.8, D-02).
 *
 * There is no conversion engine and never will be -- a live rate needs
 * `INTERNET`, which Law 6 forbids. The user enters what their bank actually
 * charged them, which is the correct figure anyway: it already carries the
 * markup and fees a mid-market rate would miss.
 */
public data class ForeignAmount(
    /** In [currency]'s minor units, not the base currency's. */
    val amountMinor: Long,
    val currency: String,
    /** Rate x 1e6. User-entered or derived from the two amounts. Never fetched. */
    val fxRateMicro: Long,
)

/**
 * One line of a multi-line entry (SPEC.md §6.1).
 *
 * **Sign convention:** [total] is signed, and the line items of an entry sum to
 * the entry's amount. `DISCOUNT` rows are therefore negative and `UNALLOCATED`
 * may be either. §5.3 states reconciliation as
 * `|Σ(items) + Σ(tax) − Σ(discount) − total|`, which is the same arithmetic
 * with the sign moved from the row to the formula; carrying it on the row means
 * every consumer that sums line items gets the right answer without knowing
 * what a `kind` means.
 */
public data class LineItem(
    val id: String,
    val position: Int,
    val name: String,
    val quantityMilli: Long,
    val unitPrice: Money?,
    val total: Money,
    val kind: LineItemKind,
    val categoryId: String?,
    val subcategoryId: String?,
) {
    public companion object {
        /** 1.000 = 1000, so half a kilo is representable without a `Double`. */
        public const val UNIT_QUANTITY_MILLI: Long = 1000L
    }
}
