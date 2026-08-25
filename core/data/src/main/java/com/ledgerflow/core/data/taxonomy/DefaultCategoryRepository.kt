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
import com.ledgerflow.core.domain.vault.StorageMaintenance
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryPalette
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val storage: StorageMaintenance,
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
            val entries = database.ledgerTaxonomyDao()

            // `is_system` is provenance -- "we shipped this row" -- not a
            // permission. It used to block deletion, which made the *entire*
            // seed set permanently undeletable, because every seeded row
            // carries the flag. SPEC.md §5.5 says the opposite: the seed set
            // ships "all editable", and categories are soft-deletable through
            // the re-assign flow below.
            //
            // Nothing is lost by allowing it. `ledger_entry.category_id` is
            // nullable with ON DELETE SET NULL, and the re-assign check a few
            // lines down already refuses to orphan entries silently -- so
            // "there must always be somewhere for uncategorised spend to go"
            // is a property the schema and that check already guarantee,
            // rather than one this flag was holding up.
            val existing = dao.byId(id)
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
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
                    // `InvalidTarget`, not `InvalidParent`. The two were the
                    // same case, and picking a destination that had been hidden
                    // in another tab answered with "categories go two levels
                    // deep" -- a true sentence about a rule this user was not
                    // breaking.
                    return@withContext TaxonomyResult.Failure(TaxonomyError.InvalidTarget)
                }
            }

            // `withTransaction`, not `runInTransaction`: the latter takes a
            // blocking lambda and suspend DAO calls cannot run inside it.
            // Re-pointing entries and removing the category must land together
            // -- a crash between them leaves rows pointing at a deleted category.
            // One stamp for the whole branch. It is not just tidiness: the
            // timestamp is the only thing in the schema that records which
            // deletion a hidden child belonged to, and `restore` and `purge`
            // both match on it to bring back or destroy exactly the rows that
            // went out together.
            val now = clock.nowMillis()
            database.withTransaction {
                if (reassignTo != null) {
                    entries.reassignCategory(ledger, id, reassignTo, now)
                    entries.reassignLineItemCategory(ledger, id, reassignTo)
                    // Line grain too (ADR-0018). An itemised entry files only
                    // here, so moving `ledger_entry` alone would leave the very
                    // rows the count was about still pointing at the category
                    // the user just emptied.
                }
                entries.clearSubcategory(ledger, id, now)
                entries.clearLineItemSubcategory(ledger, id)
                // Children follow the parent out (BUG12).
                //
                // This was `reparentChildren(id, reassignTo, ...)`, which in the
                // no-entries path passes a null target -- so `parent_id` became
                // null and the subcategories were promoted to top-level
                // categories the user never created. The comment here has always
                // said they "follow the parent out"; now they do. It matters more
                // than it reads: hiding is recoverable since ADR-0016, and a
                // branch that comes back a different shape from the one that was
                // deleted is not a restore.
                dao.softDeleteChildren(id, now)
                dao.softDelete(id, now)
            }
            TaxonomyResult.Success(Unit)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeHidden(ledger: LedgerType): Flow<List<HiddenTaxonomy>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.categoryDao() ?: return@flatMapLatest flowOf(emptyList())
            // Both reads, because the shape of the list is a fact about pairs of
            // rows: whether a hidden child is its own restorable row depends on
            // its parent, and naming that parent needs the live set.
            combine(dao.observeHidden(ledger), dao.observeLive(ledger)) { hidden, live ->
                val liveNames = live.associate { it.id to it.name }
                hidden.mapNotNull { row -> row.asHiddenRow(hidden, liveNames) }
            }
        }

    /**
     * Un-hides a category, and the branch it went out with.
     *
     * Two directions, and both are forced rather than chosen:
     *
     * - Restoring a **parent** brings back the subcategories stamped with its
     *   own `deleted_at`. Anything else restores a branch as a bare parent and
     *   silently drops the children the user was looking at when they deleted it.
     * - Restoring a **child** whose parent is still hidden brings the parent
     *   back too, because `observeTree` builds children off live parents. A live
     *   subcategory under a hidden parent is not a broken layout, it is an
     *   invisible row: it exists, entries can still point at it, and no screen
     *   in the app will ever show it.
     *
     * Entries are not un-re-assigned. Moving them was a decision the user made
     * at delete time, in a dialog that named the count, and reversing it here
     * would rewrite filings they may have relied on since.
     */
    override suspend fun restore(id: String): TaxonomyResult<Unit> = withContext(io) {
        val database = session.requireDatabase()
        val dao = database.categoryDao()

        val hidden = dao.byId(id)?.takeIf { it.deletedAt != 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        val parent = hidden.parentId?.let { dao.byId(it) }?.takeIf { it.deletedAt != 0L }

        // §6.1.1's index is (parent_key, name, ledger_scope, deleted_at), so a
        // hidden "Food" and a live "Food" coexist happily and collide the
        // instant one comes back. Checked here, in words, rather than left to
        // surface as a constraint violation.
        nameClash(dao, hidden)?.let { return@withContext it }
        parent?.let { nameClash(dao, it)?.let { clash -> return@withContext clash } }

        database.withTransaction {
            parent?.let { dao.restore(it.id) }
            dao.restore(id)
            if (hidden.parentId == null) dao.restoreChildren(id, hidden.deletedAt)
        }
        TaxonomyResult.Success(Unit)
    }

    /**
     * **Destroys a hidden category and the branch it went out with.** Only
     * `PurgeHiddenCategoryUseCase` may call this (ADR-0016).
     *
     * `ledger_entry.category_id` has **no foreign key**, which makes this the
     * quieter of the two dangerous purges: SQLite will not refuse the delete and
     * will not repair anything after it, so an entry left behind simply holds an
     * id that resolves to no row and renders as unfiled. The count below is the
     * only thing that prevents it, and it counts **binned entries too** -- a
     * `deleted_at IS NULL` predicate here would let a purge quietly strip the
     * category off rows still sitting in the bin.
     *
     * **It counts line items too, since ADR-0018**, and that was a real hole
     * rather than a theoretical one. `line_item.category_id` has no foreign key
     * either, and an itemised entry files *only* there -- so a category used by
     * nothing but line items counted 0, the block never fired, and erasing it
     * silently detached every one of those lines. `countAllForCategory` now
     * asks about both grains; the reassign below moves both.
     *
     * The children are counted as well as destroyed. A branch's entries are
     * usually filed under the parent, but nothing stops an entry naming a
     * subcategory directly as its `category_id`, and that entry needs a
     * destination exactly as much as its siblings do.
     *
     * A `subcategory_id` reference is cleared rather than re-assigned, which is
     * what a soft delete has always done to one: the entry keeps the category it
     * was filed under and loses a detail. Only `category_id` references need
     * somewhere to go, which is why [TaxonomyError.ReassignRequired] counts
     * those alone.
     */
    override suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit> =
        withContext(io) {
            val database = session.requireDatabase()
            val dao = database.categoryDao()
            val entries = database.ledgerTaxonomyDao()

            val hidden = dao.byId(id)?.takeIf { it.deletedAt != 0L }
                ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
            if (reassignTo == id) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
            }

            val ledger = hidden.ledgerScope
            val branch = if (hidden.parentId == null) {
                dao.hiddenChildren(id, hidden.deletedAt)
            } else {
                emptyList()
            }
            val doomed = listOf(hidden.id) + branch.map { it.id }

            val affected = doomed.sumOf { entries.countAllForCategory(ledger, it) }
            if (affected > 0 && reassignTo == null) {
                return@withContext TaxonomyResult.Failure(
                    TaxonomyError.ReassignRequired(affected),
                )
            }
            if (reassignTo != null) {
                val target = dao.byId(reassignTo)
                val usable = target != null &&
                    target.deletedAt == 0L &&
                    target.ledgerScope == ledger
                if (!usable) {
                    return@withContext TaxonomyResult.Failure(TaxonomyError.InvalidTarget)
                }
            }

            val now = clock.nowMillis()
            val destroyed = database.withTransaction {
                doomed.forEach { doomedId ->
                    if (reassignTo != null) {
                        entries.reassignCategory(ledger, doomedId, reassignTo, now)
                        entries.reassignLineItemCategory(ledger, doomedId, reassignTo)
                    }
                    // Always, target or not: this reference has nowhere to go
                    // and cannot be left pointing at a row about to stop
                    // existing. Both grains, for the same reason -- and it
                    // matters more here than on the soft delete, because after
                    // this transaction the category row is gone and a missed
                    // reference resolves to nothing forever.
                    entries.clearSubcategory(ledger, doomedId, now)
                    entries.clearLineItemSubcategory(ledger, doomedId)
                }
                if (branch.isNotEmpty()) dao.hardDeleteChildren(id, hidden.deletedAt)
                dao.hardDelete(id)
            }
            // Outside the transaction: SQLite refuses to VACUUM inside one.
            if (destroyed > 0) storage.compactStorage()
            TaxonomyResult.Success(Unit)
        }

    /** The refusal §6.1.1's index would otherwise raise as a constraint violation. */
    private suspend fun nameClash(
        dao: com.ledgerflow.core.database.dao.CategoryDao,
        row: CategoryEntity,
    ): TaxonomyResult.Failure? =
        if (dao.findLiveByName(row.parentKey, row.name, row.ledgerScope) != null) {
            TaxonomyResult.Failure(TaxonomyError.DuplicateName(row.name))
        } else {
            null
        }

    /**
     * One hidden row as the list should show it, or nothing.
     *
     * Nothing when the row is a subcategory that went out **with** its parent:
     * the branch was deleted as a unit and is restored as one, so listing its
     * pieces separately would offer four undo buttons for one action and let
     * the user reassemble a tree they never took apart. The parent's row says
     * how many came with it instead.
     *
     * A subcategory hidden on its own keeps its own row, and names the parent it
     * belongs under -- which is the fact that tells "Groceries" hidden from
     * Food apart from "Groceries" hidden from Household.
     */
    private fun CategoryEntity.asHiddenRow(
        hidden: List<CategoryEntity>,
        liveNames: Map<String, String>,
    ): HiddenTaxonomy? {
        if (parentId != null) {
            val wentOutWithParent = hidden.any { it.id == parentId && it.deletedAt == deletedAt }
            if (wentOutWithParent) return null
            return HiddenTaxonomy(
                id = id,
                name = name,
                hiddenAt = deletedAt,
                detail = liveNames[parentId]?.let { "under $it" },
            )
        }
        val children = hidden.count { it.parentId == id && it.deletedAt == deletedAt }
        return HiddenTaxonomy(
            id = id,
            name = name,
            hiddenAt = deletedAt,
            detail = when (children) {
                0 -> null
                1 -> "with 1 subcategory"
                else -> "with $children subcategories"
            },
        )
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
