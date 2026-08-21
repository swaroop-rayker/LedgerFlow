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
import com.ledgerflow.core.domain.vault.StorageMaintenance
import com.ledgerflow.core.model.HiddenTaxonomy
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
    private val storage: StorageMaintenance,
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

        // A *hidden* row still occupies the key, and this is where that used to
        // become a crash (BUG11). `index_merchant_normalized_key` is
        // `UNIQUE (normalized_key)` with no `deleted_at` in it, while the
        // lookup above filters hidden rows out -- so a user who hid "Amazon"
        // and then added "Amazon" fell through to an insert that violated the
        // constraint, and `OnConflictStrategy.ABORT` raised
        // `SQLiteConstraintException` out of a repository whose entire contract
        // is typed refusals.
        //
        // Un-hiding is not a workaround for the constraint; it is what the user
        // asked for. They named a merchant this install already has, and the
        // row that comes back brings its aliases and default category with it.
        dao.byNormalizedKeyAny(key)?.let { hidden ->
            dao.restore(hidden.id)
            return@withContext TaxonomyResult.Success(hidden.copy(deletedAt = 0L).toDomain())
        }

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
     *
     * **The clash check reads hidden rows too, and that is BUG11's second
     * door.** It used to bind `deleted_at = 0` like every other lookup here, so
     * renaming "DMart" onto a hidden "Big Bazaar" found no clash, fell through
     * to the update, and hit `UNIQUE constraint failed: merchant.normalized_key`
     * -- the same uncaught `SQLiteConstraintException` `createOrGet` used to
     * raise, from a different method. Fixing only `createOrGet` left this one
     * live, and `TaxonomyPurgeTest` found it by trying to *set up* a different
     * scenario.
     *
     * The two doors need different answers, though. `createOrGet` restores the
     * hidden row, because someone typing a merchant's name wants that merchant.
     * A rename cannot: the row being renamed already exists, so un-hiding would
     * leave two rows on one key. It refuses, and the message says which of
     * restore or erase clears the way.
     */
    override suspend fun rename(id: String, canonicalName: String): TaxonomyResult<Unit> =
        withContext(io) {
            val name = canonicalName.trim()
            if (name.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

            val dao = session.requireDatabase().merchantDao()
            val existing = dao.byId(id)
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

            val key = MerchantNormalizer.normalize(name)
            val clash = dao.byNormalizedKeyAny(key)
            if (clash != null && clash.id != id) {
                return@withContext TaxonomyResult.Failure(
                    if (clash.deletedAt == 0L) {
                        TaxonomyError.DuplicateName(name)
                    } else {
                        TaxonomyError.NameHeldByHiddenRow(clash.canonicalName)
                    },
                )
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
            val entries = database.ledgerTaxonomyDao()

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
        //
        // Recoverable since ADR-0016: [observeHidden] lists it and [restore]
        // brings it back. Before that this was a one-way door with no surface
        // anywhere in the app that could reopen it.
        dao.softDelete(id, clock.nowMillis())
        TaxonomyResult.Success(Unit)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeHidden(): Flow<List<HiddenTaxonomy>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.merchantDao()?.observeHidden()?.map { rows ->
                rows.map { HiddenTaxonomy(it.id, it.canonicalName, it.deletedAt) }
            } ?: flowOf(emptyList())
        }

    /**
     * Un-hides a merchant, unless its key has been taken while it was away.
     *
     * **Defence in depth rather than a path the UI can reach.** Once
     * `createOrGet` restores a hidden match and `rename` refuses one, no write
     * in the app can put a second row on a hidden merchant's key -- the index is
     * effectively unique across live *and* hidden rows, which is what it always
     * claimed to be. The check stays because that is a property of three methods
     * agreeing, not of the schema: a `.lfbk` restore replays rows directly, and
     * the failure mode without this is a constraint violation thrown out of a
     * button labelled "Restore".
     */
    override suspend fun restore(id: String): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().merchantDao()
        val hidden = dao.byId(id)?.takeIf { it.deletedAt != 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

        if (dao.byNormalizedKey(hidden.normalizedKey) != null) {
            return@withContext TaxonomyResult.Failure(
                TaxonomyError.DuplicateName(hidden.canonicalName),
            )
        }
        if (dao.restore(id) == 0) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        }
        TaxonomyResult.Success(Unit)
    }

    /**
     * **Destroys a hidden merchant.** Only `PurgeHiddenMerchantUseCase` may
     * call this (ADR-0016).
     *
     * The count is the whole safety mechanism, because the schema provides
     * none: `ledger_entry.merchant_id` is `ON DELETE SET NULL`, so a bare
     * `DELETE` here would succeed, report success, and leave every entry that
     * ever used this shop holding an amount with no name against it.
     *
     * Two properties of the count are easy to get wrong and both are deliberate:
     *
     * - **Binned entries count.** `countAllForMerchant` omits the
     *   `deleted_at IS NULL` that `countForMerchant` binds. An entry in the bin
     *   is restorable, and restoring it to find its merchant gone is the
     *   failure this prevents.
     * - **Both books.** A refund from a shop is a credit, so a merchant lives in
     *   both. Iterating `LedgerType` rather than dropping the predicate keeps
     *   every statement inside one ledger (ADR-0002).
     *
     * The re-point and the destroy share a transaction: a crash between them
     * would leave entries pointing at a merchant that no longer exists, which
     * no foreign key here would repair.
     */
    override suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit> =
        withContext(io) {
            val database = session.requireDatabase()
            val dao = database.merchantDao()
            val entries = database.ledgerTaxonomyDao()

            dao.byId(id)?.takeIf { it.deletedAt != 0L }
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
            if (reassignTo == id) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
            }

            val affected = LedgerType.entries.sumOf { entries.countAllForMerchant(it, id) }
            if (affected > 0 && reassignTo == null) {
                return@withContext TaxonomyResult.Failure(
                    TaxonomyError.ReassignRequired(affected),
                )
            }
            if (reassignTo != null && dao.byId(reassignTo)?.takeIf { it.deletedAt == 0L } == null) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.InvalidTarget)
            }

            val destroyed = database.withTransaction {
                if (reassignTo != null) {
                    LedgerType.entries.forEach { ledger ->
                        entries.reassignMerchant(ledger, id, reassignTo, clock.nowMillis())
                    }
                }
                dao.hardDelete(id)
            }
            // Outside the transaction, and only if something went: SQLite
            // refuses to VACUUM inside one, and rewriting the whole encrypted
            // database to reclaim nothing is the most expensive no-op there is.
            if (destroyed > 0) storage.compactStorage()
            TaxonomyResult.Success(Unit)
        }
}
