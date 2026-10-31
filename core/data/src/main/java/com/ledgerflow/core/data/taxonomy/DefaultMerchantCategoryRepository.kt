package com.ledgerflow.core.data.taxonomy

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.MerchantCategoryEntity
import com.ledgerflow.core.domain.taxonomy.MerchantCategoryRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.CategoryPalette
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.MerchantCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Merchant categories over Room (V2-PLAN §5, schema v13).
 *
 * The only class that writes `merchant.merchant_category_id`
 * (`MerchantCategorySingleWriterTest`): categorising is the user's, from the
 * Merchants section (§5.2).
 */
@Singleton
public class DefaultMerchantCategoryRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MerchantCategoryRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<MerchantCategory>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.merchantCategoryDao()?.observeLive()?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeHidden(): Flow<List<HiddenTaxonomy>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.merchantCategoryDao()?.observeHidden()?.map { rows ->
                rows.map { HiddenTaxonomy(id = it.id, name = it.name, hiddenAt = it.deletedAt) }
            } ?: flowOf(emptyList())
        }

    override suspend fun create(name: String, colorArgb: Int?): TaxonomyResult<MerchantCategory> =
        withContext(io) {
            val clean = name.trim()
            if (clean.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)
            val dao = session.requireDatabase().merchantCategoryDao()
            if (dao.liveByName(clean) != null) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(clean))
            }
            val order = dao.maxSortOrder() + 1
            val row = MerchantCategoryEntity(
                id = ids.generate(),
                name = clean,
                colorArgb = colorArgb ?: CategoryPalette.forIndex(order),
                sortOrder = order,
            )
            dao.insert(row)
            TaxonomyResult.Success(row.toDomain())
        }

    override suspend fun rename(id: String, name: String): TaxonomyResult<Unit> = withContext(io) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)
        val dao = session.requireDatabase().merchantCategoryDao()
        val existing = dao.byId(id)?.takeIf { it.deletedAt == 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        val holder = dao.liveByName(clean)
        if (holder != null && holder.id != id) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(clean))
        }
        dao.update(existing.copy(name = clean))
        TaxonomyResult.Success(Unit)
    }

    override suspend fun recolor(id: String, colorArgb: Int): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().merchantCategoryDao()
        val existing = dao.byId(id)?.takeIf { it.deletedAt == 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        dao.update(existing.copy(colorArgb = colorArgb))
        TaxonomyResult.Success(Unit)
    }

    override suspend fun delete(id: String, moveTo: String?): TaxonomyResult<Int> = withContext(io) {
        if (id == moveTo) return@withContext TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
        val database = session.requireDatabase()
        val categories = database.merchantCategoryDao()
        categories.byId(id)?.takeIf { it.deletedAt == 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        if (moveTo != null && categories.byId(moveTo)?.takeIf { it.deletedAt == 0L } == null) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        }
        // One transaction: a category hidden with merchants still filed under
        // it would leave them in a bucket the user can no longer see or choose.
        val moved = database.withTransaction {
            val count = database.merchantDao().moveMerchantCategory(from = id, to = moveTo)
            categories.softDelete(id, clock.nowMillis())
            count
        }
        TaxonomyResult.Success(moved)
    }

    override suspend fun restore(id: String): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().merchantCategoryDao()
        val hidden = dao.byId(id)?.takeIf { it.deletedAt != 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        if (dao.liveByName(hidden.name) != null) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(hidden.name))
        }
        dao.restore(id)
        TaxonomyResult.Success(Unit)
    }

    override suspend fun setCategory(merchantIds: List<String>, categoryId: String?): TaxonomyResult<Int> =
        withContext(io) {
            if (merchantIds.isEmpty()) return@withContext TaxonomyResult.Success(0)
            val database = session.requireDatabase()
            if (categoryId != null &&
                database.merchantCategoryDao().byId(categoryId)?.takeIf { it.deletedAt == 0L } == null
            ) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
            }
            TaxonomyResult.Success(database.merchantDao().setMerchantCategory(merchantIds, categoryId))
        }

    override suspend fun seedDefaultsOnce(): Int = withContext(io) {
        val database = session.requireDatabase()
        val meta = database.appMetaDao()
        if (meta.value(AppMetaEntity.KEY_MERCHANT_CATEGORIES_SEEDED) != null) return@withContext 0
        database.withTransaction {
            val dao = database.merchantCategoryDao()
            // A restored v13 backup arrives with its categories and its marker;
            // a v12 one with neither. Never write a default over a name the
            // user already has.
            val base = dao.maxSortOrder() + 1
            val rows = DefaultMerchantCategories.names
                .filter { dao.liveByName(it) == null }
                .mapIndexed { index, name ->
                    MerchantCategoryEntity(
                        id = ids.generate(),
                        name = name,
                        colorArgb = CategoryPalette.forIndex(index),
                        sortOrder = base + index,
                        isSystem = true,
                    )
                }
            dao.insertAll(rows)
            meta.put(AppMetaEntity(AppMetaEntity.KEY_MERCHANT_CATEGORIES_SEEDED, clock.nowMillis().toString()))
            rows.size
        }
    }
}

/**
 * The starter merchant categories (V2-PLAN §5.2; confirmed by the owner,
 * 2026-10-31). A short set to rename, hide or extend; no merchant is filed
 * under any of them.
 */
internal object DefaultMerchantCategories {
    val names: List<String> = listOf(
        "Groceries",
        "Food delivery",
        // "cafés": the é as a code point (CLAUDE.md §5).
        "Restaurants & caf" + Char(0x00E9) + "s",
        "Fuel",
        "Pharmacy & health",
        "Utilities & bills",
        "Shopping",
        "Travel & transport",
        "Entertainment",
        "Education",
        "Services",
        "Government & tax",
    )
}

internal fun MerchantCategoryEntity.toDomain(): MerchantCategory = MerchantCategory(
    id = id,
    name = name,
    colorArgb = colorArgb,
    sortOrder = sortOrder,
    isSystem = isSystem,
)
