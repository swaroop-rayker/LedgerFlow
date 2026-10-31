package com.ledgerflow.core.testing.taxonomy

import com.ledgerflow.core.domain.taxonomy.MerchantCategoryRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.MerchantCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Merchant categories in memory (V2-PLAN §5), with each merchant's filing in
 * [filed] so a test can see what "Set category" did.
 */
public class FakeMerchantCategoryRepository(initial: List<MerchantCategory> = emptyList()) :
    MerchantCategoryRepository {

    private val live = MutableStateFlow(initial)
    private val hidden = MutableStateFlow<List<MerchantCategory>>(emptyList())

    /** Merchant id -> category id; absent means Uncategorised. */
    public val filed: MutableMap<String, String> = mutableMapOf()

    /** How many times [seedDefaultsOnce] has been asked. */
    public var seedRequests: Int = 0
        private set

    override fun observeAll(): Flow<List<MerchantCategory>> = live

    override fun observeHidden(): Flow<List<HiddenTaxonomy>> =
        hidden.map { rows -> rows.map { HiddenTaxonomy(id = it.id, name = it.name, hiddenAt = 1L) } }

    override suspend fun create(name: String, colorArgb: Int?): TaxonomyResult<MerchantCategory> {
        val clean = name.trim()
        if (clean.isEmpty()) return TaxonomyResult.Failure(TaxonomyError.BlankName)
        if (live.value.any { it.name.equals(clean, ignoreCase = true) }) {
            return TaxonomyResult.Failure(TaxonomyError.DuplicateName(clean))
        }
        val id = "mc-${live.value.size + hidden.value.size + 1}"
        val row = MerchantCategory(id, clean, colorArgb ?: 0, live.value.size, isSystem = false)
        live.value = live.value + row
        return TaxonomyResult.Success(row)
    }

    override suspend fun rename(id: String, name: String): TaxonomyResult<Unit> =
        update(id) { it.copy(name = name.trim()) }

    override suspend fun recolor(id: String, colorArgb: Int): TaxonomyResult<Unit> =
        update(id) { it.copy(colorArgb = colorArgb) }

    override suspend fun delete(id: String, moveTo: String?): TaxonomyResult<Int> {
        val row = live.value.firstOrNull { it.id == id } ?: return TaxonomyResult.Failure(TaxonomyError.NotFound)
        val moving = filed.filterValues { it == id }.keys
        moving.forEach { merchant -> if (moveTo == null) filed.remove(merchant) else filed[merchant] = moveTo }
        live.value = live.value - row
        hidden.value = hidden.value + row
        return TaxonomyResult.Success(moving.size)
    }

    override suspend fun restore(id: String): TaxonomyResult<Unit> {
        val row = hidden.value.firstOrNull { it.id == id } ?: return TaxonomyResult.Failure(TaxonomyError.NotFound)
        hidden.value = hidden.value - row
        live.value = live.value + row
        return TaxonomyResult.Success(Unit)
    }

    override suspend fun setCategory(merchantIds: List<String>, categoryId: String?): TaxonomyResult<Int> {
        merchantIds.forEach { if (categoryId == null) filed.remove(it) else filed[it] = categoryId }
        return TaxonomyResult.Success(merchantIds.size)
    }

    override suspend fun seedDefaultsOnce(): Int {
        seedRequests++
        return 0
    }

    private fun update(id: String, change: (MerchantCategory) -> MerchantCategory): TaxonomyResult<Unit> {
        val row = live.value.firstOrNull { it.id == id } ?: return TaxonomyResult.Failure(TaxonomyError.NotFound)
        live.value = live.value.map { if (it.id == id) change(row) else it }
        return TaxonomyResult.Success(Unit)
    }
}
