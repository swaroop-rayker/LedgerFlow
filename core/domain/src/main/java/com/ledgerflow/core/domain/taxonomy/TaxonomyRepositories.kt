package com.ledgerflow.core.domain.taxonomy

import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
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
