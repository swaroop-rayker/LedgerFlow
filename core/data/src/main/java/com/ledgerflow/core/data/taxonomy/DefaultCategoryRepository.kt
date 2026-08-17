package com.ledgerflow.core.data.taxonomy

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryPalette
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Categories over Room (SPEC.md §5.5).
 *
 * **This class is the sole maintainer of `parent_key`.** §6.1.1's uniqueness
 * index is `(parent_key, name, ledger_scope, deleted_at)`, and `parent_key` is
 * `COALESCE(parent_id, '')` maintained in code because SQLite has no computed
 * columns Room can express. If it ever drifts out of step with `parent_id`, the
 * index silently stops catching duplicates -- the constraint does not fail, it
 * just quietly matches nothing. `CategoryParentKeyTest` asserts it holds.
 *
 * Reads flow off [VaultSession] rather than a captured DAO: the database does
 * not exist until the vault unlocks, and a repository holding a DAO would have
 * to be constructed after that -- which Hilt cannot express without making the
 * whole graph lazy.
 */
@Singleton
public class DefaultCategoryRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CategoryRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observe(ledger: LedgerType): Flow<List<Category>> =
        session.whenUnlocked()
            .flatMapLatest { database ->
                database?.categoryDao()?.observeLive(ledger)?.map { rows ->
                    rows.map { it.toDomain() }
                } ?: flowOf(emptyList())
            }

    override fun observeTree(ledger: LedgerType): Flow<List<CategoryTree>> =
        observe(ledger).map { categories ->
            val childrenByParent = categories.filter { it.isSubcategory }.groupBy { it.parentId }
            categories
                .filterNot { it.isSubcategory }
                .sortedBy { it.sortOrder }
                .map { parent ->
                    CategoryTree(
                        parent = parent,
                        children = childrenByParent[parent.id].orEmpty().sortedBy { it.sortOrder },
                    )
                }
        }

    override suspend fun find(id: String): Category? = withContext(io) {
        session.requireDatabase().categoryDao().byId(id)?.takeIf { it.deletedAt == 0L }?.toDomain()
    }

    override suspend fun create(request: NewCategory): TaxonomyResult<Category> = withContext(io) {
        val name = request.name.trim()
        if (name.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

        val dao = session.requireDatabase().categoryDao()

        // Two levels, and the parent must be in the same book. Checked here
        // because neither is expressible as a SQLite constraint -- a CHECK
        // cannot run a subquery (§6.1.1).
        val requestedParentId = request.parentId
        if (requestedParentId != null) {
            val parent = dao.byId(requestedParentId)
            val validParent = parent != null &&
                parent.deletedAt == 0L &&
                parent.parentId == null &&
                parent.ledgerScope == request.ledger
            if (!validParent) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.InvalidParent)
            }
        }

        val parentKey = parentKeyOf(request.parentId)
        if (dao.findLiveByName(parentKey, name, request.ledger) != null) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(name))
        }

        val entity = CategoryEntity(
            id = ids.generate(),
            parentId = request.parentId,
            parentKey = parentKey,
            ledgerScope = request.ledger,
            name = name,
            icon = request.icon,
            colorArgb = request.colorArgb ?: CategoryPalette.forIndex(dao.count()),
            sortOrder = dao.count(),
            isSystem = false,
        )
        dao.insert(entity)
        TaxonomyResult.Success(entity.toDomain())
    }

    override suspend fun rename(id: String, name: String): TaxonomyResult<Unit> = withContext(io) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

        val dao = session.requireDatabase().categoryDao()
        val existing = dao.byId(id)
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

        val clash = dao.findLiveByName(existing.parentKey, trimmed, existing.ledgerScope)
        if (clash != null && clash != id) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(trimmed))
        }
        // System categories are renameable -- §5.5 says the shipped set is fully
        // editable. Only deletion is protected.
        dao.update(existing.copy(name = trimmed))
        TaxonomyResult.Success(Unit)
    }

    override suspend fun updateAppearance(
        id: String,
        icon: String,
        colorArgb: Int,
    ): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().categoryDao()
        val existing = dao.byId(id)
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        dao.update(existing.copy(icon = icon, colorArgb = colorArgb))
        TaxonomyResult.Success(Unit)
    }

    override suspend fun delete(id: String, reassignTo: String?): TaxonomyResult<Unit> =
        withContext(io) {
            val database = session.requireDatabase()
            val dao = database.categoryDao()
            val entries = database.ledgerEntryDao()

            val existing = dao.byId(id)
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
            if (existing.isSystem) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.SystemProtected)
            }
            if (reassignTo == id) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
            }

            val ledger = existing.ledgerScope
            val affected = entries.countForCategory(ledger, id)
            if (affected > 0 && reassignTo == null) {
                // Refused rather than orphaning the entries. The caller asks the
                // user where they should go and calls back with an answer.
                return@withContext TaxonomyResult.Failure(TaxonomyError.ReassignRequired(affected))
            }
            if (reassignTo != null) {
                val target = dao.byId(reassignTo)
                val usable = target != null &&
                    target.deletedAt == 0L &&
                    target.ledgerScope == ledger
                if (!usable) {
                    return@withContext TaxonomyResult.Failure(TaxonomyError.InvalidParent)
                }
            }

            // `withTransaction`, not `runInTransaction`: the latter takes a
            // blocking lambda and suspend DAO calls cannot run inside it.
            // Re-pointing entries and removing the category must land together
            // -- a crash between them leaves rows pointing at a deleted category.
            database.withTransaction {
                if (reassignTo != null) {
                    entries.reassignCategory(ledger, id, reassignTo, clock.nowMillis())
                }
                entries.clearSubcategory(ledger, id, clock.nowMillis())
                // Children follow the parent out rather than becoming orphaned
                // top-level categories the user never created.
                dao.reparentChildren(id, reassignTo, parentKeyOf(reassignTo))
                dao.softDelete(id, clock.nowMillis())
            }
            TaxonomyResult.Success(Unit)
        }

    override suspend fun seedSystemDefaults(): Int = withContext(io) {
        val dao = session.requireDatabase().categoryDao()
        // Idempotent: re-running must never duplicate the starter set, and this
        // runs on a path (vault creation) that can be retried after a failure.
        if (dao.count() > 0) return@withContext 0

        val rows = mutableListOf<CategoryEntity>()
        var order = 0
        DefaultTaxonomy.categories.forEach { (ledger, groups) ->
            groups.forEach { group ->
                val parentId = ids.generate()
                rows += CategoryEntity(
                    id = parentId,
                    parentId = null,
                    parentKey = parentKeyOf(null),
                    ledgerScope = ledger,
                    name = group.name,
                    icon = group.icon,
                    colorArgb = CategoryPalette.forIndex(order),
                    sortOrder = order++,
                    isSystem = true,
                )
                group.children.forEach { child ->
                    rows += CategoryEntity(
                        id = ids.generate(),
                        parentId = parentId,
                        parentKey = parentKeyOf(parentId),
                        ledgerScope = ledger,
                        name = child,
                        icon = group.icon,
                        colorArgb = CategoryPalette.forIndex(order),
                        sortOrder = order++,
                        isSystem = true,
                    )
                }
            }
        }
        dao.insertAll(rows)
        rows.size
    }
}
