package com.ledgerflow.core.data.taxonomy

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Merchants over Room (SPEC.md §5.5). */
@Singleton
public class DefaultMerchantRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MerchantRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<Merchant>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.merchantDao()?.observeLive()?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    override suspend fun find(id: String): Merchant? = withContext(io) {
        session.requireDatabase().merchantDao().byId(id)
            ?.takeIf { it.deletedAt == 0L }
            ?.toDomain()
    }

    override suspend fun findByName(rawName: String): Merchant? = withContext(io) {
        val key = MerchantNormalizer.normalize(rawName)
        if (key.isEmpty()) return@withContext null
        session.requireDatabase().merchantDao().byNormalizedKey(key)?.toDomain()
    }

    /**
     * Get-or-create rather than create.
     *
     * `normalized_key` is `UNIQUE`, so a plain insert of a name that normalises
     * onto an existing merchant throws a constraint violation -- and the caller
     * almost always wanted the existing row anyway. Returning it is both the
     * useful behaviour and the one that cannot fail.
     */
    override suspend fun createOrGet(
        rawName: String,
        defaultCategoryId: String?,
    ): TaxonomyResult<Merchant> = withContext(io) {
        val name = rawName.trim()
        if (name.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

        val key = MerchantNormalizer.normalize(name)
        if (key.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

        val dao = session.requireDatabase().merchantDao()
        dao.byNormalizedKey(key)?.let { return@withContext TaxonomyResult.Success(it.toDomain()) }

        val entity = MerchantEntity(
            id = ids.generate(),
            canonicalName = name,
            normalizedKey = key,
            defaultCategoryId = defaultCategoryId,
            logoRef = null,
        )
        dao.insert(entity)
        TaxonomyResult.Success(entity.toDomain())
    }

    /**
     * Renaming re-derives `normalized_key`, which can collide with another
     * merchant -- at which point the honest answer is "these are the same shop,
     * merge them", not a silent constraint violation.
     */
    override suspend fun rename(id: String, canonicalName: String): TaxonomyResult<Unit> =
        withContext(io) {
            val name = canonicalName.trim()
            if (name.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

            val dao = session.requireDatabase().merchantDao()
            val existing = dao.byId(id)
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

            val key = MerchantNormalizer.normalize(name)
            val clash = dao.byNormalizedKey(key)
            if (clash != null && clash.id != id) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(name))
            }
            dao.update(existing.copy(canonicalName = name, normalizedKey = key))
            TaxonomyResult.Success(Unit)
        }

    override suspend fun setDefaultCategory(
        id: String,
        categoryId: String?,
    ): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().merchantDao()
        val existing = dao.byId(id)
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        dao.update(existing.copy(defaultCategoryId = categoryId))
        TaxonomyResult.Success(Unit)
    }

    override suspend fun merge(sourceId: String, targetId: String): TaxonomyResult<Unit> =
        withContext(io) {
            if (sourceId == targetId) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
            }
            val database = session.requireDatabase()
            val dao = database.merchantDao()
            val entries = database.ledgerEntryDao()

            dao.byId(sourceId) ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
            val target = dao.byId(targetId)?.takeIf { it.deletedAt == 0L }
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

            database.withTransaction {
                // A merchant appears in both books -- a refund from a shop is a
                // credit. Iterating the partition rather than dropping the
                // predicate keeps every statement inside one ledger (ADR-0002).
                LedgerType.entries.forEach { ledger ->
                    entries.reassignMerchant(ledger, sourceId, target.id, clock.nowMillis())
                }
                dao.softDelete(sourceId, clock.nowMillis())
            }
            TaxonomyResult.Success(Unit)
        }

    override suspend fun delete(id: String): TaxonomyResult<Unit> = withContext(io) {
        val database = session.requireDatabase()
        val dao = database.merchantDao()
        dao.byId(id) ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

        // Soft delete only, and entries keep pointing at the row. A merchant is
        // a label on history; erasing it from past entries would rewrite what
        // the user actually recorded.
        dao.softDelete(id, clock.nowMillis())
        TaxonomyResult.Success(Unit)
    }
}
