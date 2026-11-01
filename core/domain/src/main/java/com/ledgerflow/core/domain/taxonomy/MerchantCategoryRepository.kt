package com.ledgerflow.core.domain.taxonomy

import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.MerchantCategory
import kotlinx.coroutines.flow.Flow

/**
 * Merchant categories (V2-PLAN §5, V2-6): what kind of place a merchant is.
 *
 * **Categorising happens in one place** (§5.2): [setCategory] is the only way a
 * merchant's category changes, and only the Merchants section in Organise
 * calls it. Capture, review and the entry form never do;
 * `MerchantCategorySingleWriterTest` holds that.
 *
 * Re-categorising is retroactive by design: Analytics reads a merchant's
 * category at query time, so moving a merchant moves all of its history (the
 * user is correcting a label, not recording a change in their spending).
 */
public interface MerchantCategoryRepository : MerchantCategoryReader {

    /** Hidden categories, most recently hidden first (ADR-0016's lifecycle). */
    public fun observeHidden(): Flow<List<HiddenTaxonomy>>

    public suspend fun create(name: String, colorArgb: Int? = null): TaxonomyResult<MerchantCategory>

    public suspend fun rename(id: String, name: String): TaxonomyResult<Unit>

    public suspend fun recolor(id: String, colorArgb: Int): TaxonomyResult<Unit>

    /**
     * Hides [id] and moves its merchants to [moveTo], or to Uncategorised when
     * null (§5.1: deleting asks where its merchants go). Never refuses for
     * want of a destination: Uncategorised always exists.
     *
     * @return how many merchants moved.
     */
    public suspend fun delete(id: String, moveTo: String?): TaxonomyResult<Int>

    /**
     * Un-hides [id]. Its former merchants stay where [delete] moved them:
     * that was the user's decision, and reversing it would re-file merchants
     * they may since have filed again. Fails with [TaxonomyError.DuplicateName]
     * if a live category has taken the name meanwhile.
     */
    public suspend fun restore(id: String): TaxonomyResult<Unit>

    /**
     * Files [merchantIds] under [categoryId], or Uncategorised for null. The
     * Merchants section's "Set category", for one merchant or many.
     *
     * @return how many merchants changed.
     */
    public suspend fun setCategory(merchantIds: List<String>, categoryId: String?): TaxonomyResult<Int>

    /**
     * The twelve defaults (§5.2), written **once per vault**: on a new install,
     * on the first launch after the v13 migration, and after restoring a v12
     * backup. A vault whose user deleted them all is not refilled; `app_meta`
     * records that seeding happened.
     *
     * @return how many were written; 0 when this vault was already seeded.
     */
    public suspend fun seedDefaultsOnce(): Int
}
