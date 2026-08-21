package com.ledgerflow.core.domain.taxonomy

import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType
import kotlinx.coroutines.flow.Flow

/**
 * Categories and subcategories (SPEC.md §5.5).
 *
 * Every read takes a [LedgerType]. The two trees are disjoint (Law 2), so there
 * is deliberately no `observeAll()` that would hand a caller both — the absence
 * is the enforcement.
 *
 * This repository is also the sole maintainer of `category.parent_key`, the
 * `COALESCE(parent_id, '')` sentinel that makes the uniqueness index actually
 * enforce anything (§6.1.1). `CategoryParentKeyTest` asserts it stays true.
 */
public interface CategoryRepository {

    public fun observe(ledger: LedgerType): Flow<List<Category>>

    /** Top-level categories with their children, ordered for display. */
    public fun observeTree(ledger: LedgerType): Flow<List<CategoryTree>>

    public suspend fun find(id: String): Category?

    public suspend fun create(request: NewCategory): TaxonomyResult<Category>

    public suspend fun rename(id: String, name: String): TaxonomyResult<Unit>

    public suspend fun updateAppearance(
        id: String,
        icon: String,
        colorArgb: Int,
    ): TaxonomyResult<Unit>

    /**
     * Soft-deletes, moving any entries and subcategories to [reassignTo].
     *
     * @param reassignTo required when the category still has entries. Passing
     *   null against a category in use returns
     *   [TaxonomyError.ReassignRequired] with the count, so the caller can ask
     *   rather than guess. Nothing is ever hard-deleted (§5.5).
     */
    public suspend fun delete(id: String, reassignTo: String?): TaxonomyResult<Unit>

    /**
     * Hidden categories in one book, most recently hidden first (ADR-0016).
     *
     * **A branch appears once.** Deleting a category hides its subcategories
     * with it, so listing every hidden row would show a branch as four
     * unrelated entries and offer to restore the pieces separately. A hidden
     * parent is one row whose `detail` says how many children came with it; a
     * hidden subcategory appears on its own only when its parent is still live.
     */
    public fun observeHidden(ledger: LedgerType): Flow<List<HiddenTaxonomy>>

    /**
     * Un-hides a category, and its subcategories with it.
     *
     * The tree comes back the shape it left in — the inverse of [delete], which
     * takes the branch out as a unit. Entries are **not** un-re-assigned:
     * moving them was a separate decision the user made at delete time, and
     * silently reversing it would rewrite filings they may have since relied on.
     *
     * Restoring a subcategory whose parent is still hidden brings the parent
     * back too. The alternative is a live row under a hidden parent, which
     * `observeTree` cannot render — so it would exist, count, and be invisible.
     *
     * Fails with [TaxonomyError.DuplicateName] if a live sibling has taken the
     * name in the meantime; §6.1.1's index counts `deleted_at`, so the two can
     * coexist while hidden and collide the moment one comes back.
     */
    public suspend fun restore(id: String): TaxonomyResult<Unit>

    /**
     * **Destroys a hidden category. Irreversible** (ADR-0016).
     *
     * Only `PurgeHiddenCategoryUseCase` may call this, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * `ledger_entry.category_id` carries **no foreign key**, so nothing in the
     * schema refuses this and nothing repairs it afterwards — an entry left
     * behind keeps an id that resolves to no row. Hence the same rule
     * [delete] uses: if anything still references the category, this returns
     * [TaxonomyError.ReassignRequired] with the count rather than proceeding.
     *
     * **That count includes binned entries.** A purge damages a soft-deleted
     * entry exactly as much as a live one, and a binned entry can still be
     * restored from the bin.
     *
     * @param reassignTo a live category in the same book to receive the
     *   references. Required when the count is non-zero.
     */
    public suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit>

    /** Writes the shipped starter set. No-op if any category already exists. */
    public suspend fun seedSystemDefaults(): Int
}

/** A category the user is asking to create. */
public data class NewCategory(
    val ledger: LedgerType,
    val name: String,
    /** Null for a top-level category. Must belong to [ledger] and be top-level itself. */
    val parentId: String? = null,
    val icon: String = "",
    val colorArgb: Int? = null,
)

/** Canonical merchants and their aliases (SPEC.md §5.5). */
public interface MerchantRepository {

    public fun observeAll(): Flow<List<Merchant>>

    public suspend fun find(id: String): Merchant?

    /** Exact lookup on the normalized key. Used by ingest at P2. */
    public suspend fun findByName(rawName: String): Merchant?

    /** Returns the existing merchant when [rawName] normalises onto one. */
    public suspend fun createOrGet(
        rawName: String,
        defaultCategoryId: String? = null,
    ): TaxonomyResult<Merchant>

    public suspend fun rename(id: String, canonicalName: String): TaxonomyResult<Unit>

    public suspend fun setDefaultCategory(id: String, categoryId: String?): TaxonomyResult<Unit>

    /**
     * Folds [sourceId] into [targetId]: entries are repointed, then the source
     * is soft-deleted. One transaction -- a half-applied merge leaves a
     * merchant's history split across two rows with no way to tell which is which.
     */
    public suspend fun merge(sourceId: String, targetId: String): TaxonomyResult<Unit>

    public suspend fun delete(id: String): TaxonomyResult<Unit>

    /** Hidden merchants, most recently hidden first (ADR-0016). */
    public fun observeHidden(): Flow<List<HiddenTaxonomy>>

    /**
     * Un-hides a merchant.
     *
     * Fails with [TaxonomyError.DuplicateName] when another live merchant now
     * normalises onto the same key — which is the only way back for a user who
     * hid "Amazon", created "amazon.in", and wants the original returned. The
     * answer there is [merge], not a second row the unique index would refuse.
     */
    public suspend fun restore(id: String): TaxonomyResult<Unit>

    /**
     * **Destroys a hidden merchant. Irreversible** (ADR-0016).
     *
     * Only `PurgeHiddenMerchantUseCase` may call this, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * `ledger_entry.merchant_id` is `ON DELETE SET NULL`, so the database will
     * not refuse this — it will succeed and strip the shop's name off every
     * entry that ever used it, reporting nothing. That is why the check is
     * here: a non-zero reference count returns
     * [TaxonomyError.ReassignRequired] instead, and the count includes binned
     * entries and spans both books (a refund from a shop is a credit).
     *
     * `merchant_alias` rows cascade away with the merchant, which is correct —
     * an alias for a merchant that no longer exists matches nothing.
     *
     * @param reassignTo a live merchant to receive the entries. Required when
     *   the count is non-zero.
     */
    public suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit>
}

/** User-defined instruments (SPEC.md §5.5). */
public interface PaymentMethodRepository {

    public fun observeAll(): Flow<List<PaymentMethod>>

    public suspend fun find(id: String): PaymentMethod?

    public suspend fun create(request: NewPaymentMethod): TaxonomyResult<PaymentMethod>

    public suspend fun update(method: PaymentMethod): TaxonomyResult<Unit>

    /** Exactly one method is the default; setting one clears the rest. */
    public suspend fun setDefault(id: String): TaxonomyResult<Unit>

    public suspend fun delete(id: String): TaxonomyResult<Unit>

    /** Hidden payment methods, most recently hidden first (ADR-0016). */
    public fun observeHidden(): Flow<List<HiddenTaxonomy>>

    /**
     * Un-hides a payment method.
     *
     * It comes back as a non-default even if it was the default when hidden.
     * [delete] does not clear `is_default` — nothing reads the flag on a hidden
     * row, so clearing it there would be work for no one — but restoring
     * without clearing it is how an install ends up with two rows claiming to
     * be the default, and which one the entry form picks then depends on row
     * order. Making it default again is one tap.
     *
     * Past entries do **not** get their instrument back: [delete] scrubbed
     * `payment_method_id` off them, and the app has no record of which rows it
     * cleared.
     */
    public suspend fun restore(id: String): TaxonomyResult<Unit>

    /**
     * **Destroys a hidden payment method. Irreversible** (ADR-0016).
     *
     * Only `PurgeHiddenPaymentMethodUseCase` may call this, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * The only one of the three that takes no reassign argument, because by the
     * time a payment method is hidden nothing references it: [delete] already
     * cleared `payment_method_id` from every entry in both books, binned ones
     * included. The absence of the parameter is the statement that this was
     * checked, not an oversight to be corrected later.
     */
    public suspend fun purge(id: String): TaxonomyResult<Unit>

    public suspend fun seedSystemDefaults(): Int
}

public data class NewPaymentMethod(
    val type: PaymentMethodType,
    val label: String,
    val issuer: String? = null,
    val last4: String? = null,
    val colorArgb: Int? = null,
    val isDefault: Boolean = false,
)
