package com.ledgerflow.core.domain.ledger

import com.ledgerflow.core.model.LedgerType

/**
 * Ledger repository boundary result (CLAUDE.md §5).
 *
 * The same shape as `TaxonomyResult` rather than Kotlin's `Result`, and for the
 * same reason: a `Throwable` payload turns every caller into a `when` over
 * exception types, which is exception-as-control-flow wearing a functional hat
 * and gives the compiler no way to say a screen forgot a case.
 */
public sealed interface LedgerResult<out T> {

    public data class Success<out T>(val value: T) : LedgerResult<T>

    public data class Failure(val error: LedgerError) : LedgerResult<Nothing>

    public fun valueOrNull(): T? = (this as? Success)?.value
}

/**
 * Why an approval was refused.
 *
 * Every case maps to a sentence the entry form can show and the user can act
 * on. That is the bar for membership: an error a screen could only render as
 * "something went wrong" belongs in a log, not in a type the UI must exhaust.
 *
 * Several of these describe states the schema cannot forbid. `ledger_entry` has
 * no CHECK tying a subcategory to its parent -- SQLite CHECKs cannot hold a
 * subquery (§6.1.1) -- and none tying a category to the book it was filed
 * under. This type is where those invariants become enforceable.
 */
public sealed interface LedgerError {

    /** Amounts are stored positive; direction is [LedgerType], never a sign. */
    public data object AmountNotPositive : LedgerError

    /** A subcategory with no category has no parent for §6.1.1 to check against. */
    public data object SubcategoryWithoutCategory : LedgerError

    public data class UnknownCategory(val id: String) : LedgerError

    /**
     * The category belongs to the other book.
     *
     * The two trees are disjoint (Law 2), so this is not a mis-typed id: it is a
     * debit being filed under "Salary". No index or constraint catches it,
     * because both rows are individually valid.
     */
    public data class CategoryNotInLedger(val id: String, val ledger: LedgerType) : LedgerError

    /** §6.1.1's invariant, refused at the only point that can enforce it. */
    public data class SubcategoryNotUnderCategory(
        val subcategoryId: String,
        val categoryId: String,
    ) : LedgerError

    public data class UnknownMerchant(val id: String) : LedgerError

    public data class UnknownPaymentMethod(val id: String) : LedgerError

    /** The vault has no `app_meta.baseCurrency`; §7.4's gate never completed. */
    public data object BaseCurrencyMissing : LedgerError

    /**
     * Foreign capture whose currency is the base currency, which would make
     * `fx_rate_micro` meaningless and the secondary display line a lie (§5.8).
     */
    public data class ForeignCurrencyIsBase(val currency: String) : LedgerError

    /** A zero or negative rate cannot relate the two amounts. */
    public data object ForeignRateNotPositive : LedgerError

    /** A line with no name is a row nobody can identify later. */
    public data class LineItemNameBlank(val position: Int) : LedgerError

    /**
     * No live entry with this id in this book.
     *
     * Covers two cases the caller cannot distinguish and should not have to:
     * the entry was already deleted (a stale list, a second tap on a
     * confirmation), or the id belongs to the *other* book. Both mean the same
     * thing to the user -- there is nothing here to delete -- and collapsing
     * them keeps the second from becoming a way to probe the other ledger.
     */
    public data class EntryNotFound(val id: String) : LedgerError
}
