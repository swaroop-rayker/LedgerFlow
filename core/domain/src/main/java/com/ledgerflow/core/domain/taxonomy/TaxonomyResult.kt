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
     */
    public data class ReassignRequired(val affectedEntries: Int) : TaxonomyError

    /** Merging a merchant into itself, or moving entries to the row being deleted. */
    public data object SameSourceAndTarget : TaxonomyError
}
