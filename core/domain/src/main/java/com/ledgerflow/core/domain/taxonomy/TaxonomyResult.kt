package com.ledgerflow.core.domain.taxonomy

/**
 * Repository boundary result (CLAUDE.md §5): typed outcomes, never exceptions
 * as control flow.
 *
 * Kotlin's own `Result` is deliberately not used. It carries a `Throwable`, so
 * every caller ends up doing `when (error) { is DuplicateNameException -> ... }`
 * -- which is exception-as-control-flow wearing a functional hat, and gives the
 * compiler no way to tell a screen it forgot a case.
 */
public sealed interface TaxonomyResult<out T> {

    public data class Success<out T>(val value: T) : TaxonomyResult<T>

    public data class Failure(val error: TaxonomyError) : TaxonomyResult<Nothing>

    public fun valueOrNull(): T? = (this as? Success)?.value
}

/**
 * Why a taxonomy write was refused.
 *
 * Every case here maps to a sentence a user can act on. That is the bar for
 * being in this type: an error the UI can only render as "something went wrong"
 * belongs in a log, not in a sealed interface the screen has to exhaust.
 */
public sealed interface TaxonomyError {

    /** The name collides with a live sibling. `category` uniqueness (§6.1.1). */
    public data class DuplicateName(val name: String) : TaxonomyError

    /**
     * The name is held by a **hidden** merchant, which is not the same problem
     * as [DuplicateName] and must not be reported as one.
     *
     * `index_merchant_normalized_key` is `UNIQUE (normalized_key)` and does not
     * include `deleted_at`, so a hidden row keeps its key. Telling the user the
     * name "already exists" would send them looking through a list it is not in
     * -- the whole point being that it was hidden. The actionable sentence names
     * the hidden row and the two ways past it: restore it, or erase it.
     */
    public data class NameHeldByHiddenRow(val name: String) : TaxonomyError

    /** Empty or whitespace-only after trimming. */
    public data object BlankName : TaxonomyError

    public data object NotFound : TaxonomyError

    /**
     * The tree is exactly two levels (§5.5). A parent that is itself a
     * subcategory, or a parent in the other ledger, is refused here rather than
     * stored and discovered later.
     */
    public data object InvalidParent : TaxonomyError

    /**
     * Deleting a category that still has entries needs somewhere to move them.
     * Carries the count so the dialog can say how many.
     *
     * Raised by a purge too, and there it is the load-bearing check rather than
     * a courtesy: `ledger_entry.merchant_id` is `ON DELETE SET NULL` and
     * `category_id` has no key at all, so the database would let the destroy
     * through and report success (ADR-0016). The count a purge reports
     * **includes binned entries**, which the soft-delete count deliberately
     * excludes — a destroyed reference is just as gone from a row the user can
     * still restore from the bin.
     */
    public data class ReassignRequired(val affectedEntries: Int) : TaxonomyError

    /**
     * The row chosen to receive the references cannot take them: it is gone,
     * itself hidden, or in the other book.
     *
     * Distinct from [InvalidParent], which is about the *shape of the tree* —
     * "categories go two levels deep". This is about a destination, and the two
     * were the same case until a re-assign target that had been hidden in
     * another tab reported the two-levels-deep sentence, which explains nothing
     * about what went wrong.
     */
    public data object InvalidTarget : TaxonomyError

    /** Merging a merchant into itself, or moving entries to the row being deleted. */
    public data object SameSourceAndTarget : TaxonomyError
}
