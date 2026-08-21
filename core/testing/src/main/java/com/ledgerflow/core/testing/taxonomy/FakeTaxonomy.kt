package com.ledgerflow.core.testing.taxonomy

import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Recording taxonomy fakes.
 *
 * Deliberately shallow on the *rules*: uniqueness, the two-level tree and
 * transactional re-assignment are properties of the schema, and they are tested
 * instrumented against a real SQLCipher database in
 * `TaxonomyRepositoryInstrumentedTest`. Reimplementing them here would produce a
 * fake that passes tests the real repository fails — the worst kind.
 *
 * What they do faithfully is record what was asked and return what they were
 * told to. That is what a ViewModel test needs: whether a refusal became a
 * question, whether the right ledger was used, whether the user kept what they
 * typed.
 */
public class FakeCategoryRepository : CategoryRepository {

    /** Per-ledger trees, so a test can prove the partition really switched. */
    public val trees: MutableMap<LedgerType, List<CategoryTree>> = mutableMapOf()

    private val revision = MutableStateFlow(0)

    public var createResult: TaxonomyResult<Category>? = null
    public var renameResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)
    public var deleteResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    public val created: MutableList<NewCategory> = mutableListOf()
    public val renamed: MutableList<Pair<String, String>> = mutableListOf()
    public val deleted: MutableList<Pair<String, String?>> = mutableListOf()
    public var seedCalls: Int = 0
    public var seedCount: Int = 1

    /** Per-ledger hidden rows, so a test can prove the partition switched here too. */
    public val hidden: MutableMap<LedgerType, List<HiddenTaxonomy>> = mutableMapOf()

    public var restoreResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)
    public var purgeResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    public val restored: MutableList<String> = mutableListOf()

    /** Every (id, reassignTo) destroyed, in order. */
    public val purged: MutableList<Pair<String, String?>> = mutableListOf()

    override fun observe(ledger: LedgerType): Flow<List<Category>> =
        observeTree(ledger).map { tree -> tree.flatMap { listOf(it.parent) + it.children } }

    override fun observeTree(ledger: LedgerType): Flow<List<CategoryTree>> =
        revision.map { trees[ledger].orEmpty() }

    override suspend fun find(id: String): Category? =
        trees.values.flatten().flatMap { listOf(it.parent) + it.children }.firstOrNull { it.id == id }

    override suspend fun create(request: NewCategory): TaxonomyResult<Category> {
        created += request
        return createResult ?: TaxonomyResult.Success(
            Category(
                id = "generated-${created.size}",
                parentId = request.parentId,
                ledger = request.ledger,
                name = request.name,
                icon = request.icon,
                colorArgb = request.colorArgb ?: 0,
                sortOrder = created.size,
                isSystem = false,
            ),
        )
    }

    override suspend fun rename(id: String, name: String): TaxonomyResult<Unit> {
        renamed += id to name
        return renameResult
    }

    override suspend fun updateAppearance(
        id: String,
        icon: String,
        colorArgb: Int,
    ): TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    override suspend fun delete(id: String, reassignTo: String?): TaxonomyResult<Unit> {
        deleted += id to reassignTo
        return deleteResult
    }

    override fun observeHidden(ledger: LedgerType): Flow<List<HiddenTaxonomy>> =
        revision.map { hidden[ledger].orEmpty() }

    override suspend fun restore(id: String): TaxonomyResult<Unit> {
        restored += id
        return restoreResult
    }

    override suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit> {
        purged += id to reassignTo
        return purgeResult
    }

    override suspend fun seedSystemDefaults(): Int {
        seedCalls++
        return seedCount
    }
}

public class FakeMerchantRepository : MerchantRepository {

    public val merchants: MutableStateFlow<List<Merchant>> = MutableStateFlow(emptyList())

    public var createResult: TaxonomyResult<Merchant>? = null
    public var renameResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)
    public var mergeResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    public val created: MutableList<String> = mutableListOf()
    public val renamed: MutableList<Pair<String, String>> = mutableListOf()
    public val merged: MutableList<Pair<String, String>> = mutableListOf()
    public val deleted: MutableList<String> = mutableListOf()

    public val hidden: MutableStateFlow<List<HiddenTaxonomy>> = MutableStateFlow(emptyList())

    public var restoreResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)
    public var purgeResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    public val restored: MutableList<String> = mutableListOf()

    /** Every (id, reassignTo) destroyed, in order. */
    public val purged: MutableList<Pair<String, String?>> = mutableListOf()

    override fun observeAll(): Flow<List<Merchant>> = merchants

    override suspend fun find(id: String): Merchant? = merchants.value.firstOrNull { it.id == id }

    override suspend fun findByName(rawName: String): Merchant? =
        merchants.value.firstOrNull { it.canonicalName.equals(rawName, ignoreCase = true) }

    override suspend fun createOrGet(
        rawName: String,
        defaultCategoryId: String?,
    ): TaxonomyResult<Merchant> {
        created += rawName
        return createResult ?: TaxonomyResult.Success(
            Merchant("generated-${created.size}", rawName, rawName.lowercase(), defaultCategoryId, null),
        )
    }

    override suspend fun rename(id: String, canonicalName: String): TaxonomyResult<Unit> {
        renamed += id to canonicalName
        return renameResult
    }

    override suspend fun setDefaultCategory(
        id: String,
        categoryId: String?,
    ): TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    override suspend fun merge(sourceId: String, targetId: String): TaxonomyResult<Unit> {
        if (sourceId == targetId) return TaxonomyResult.Failure(TaxonomyError.SameSourceAndTarget)
        merged += sourceId to targetId
        return mergeResult
    }

    override suspend fun delete(id: String): TaxonomyResult<Unit> {
        deleted += id
        return TaxonomyResult.Success(Unit)
    }

    override fun observeHidden(): Flow<List<HiddenTaxonomy>> = hidden

    override suspend fun restore(id: String): TaxonomyResult<Unit> {
        restored += id
        return restoreResult
    }

    override suspend fun purge(id: String, reassignTo: String?): TaxonomyResult<Unit> {
        purged += id to reassignTo
        return purgeResult
    }
}

public class FakePaymentMethodRepository : PaymentMethodRepository {

    public val methods: MutableStateFlow<List<PaymentMethod>> = MutableStateFlow(emptyList())

    public var createResult: TaxonomyResult<PaymentMethod>? = null

    public val created: MutableList<NewPaymentMethod> = mutableListOf()
    public val defaulted: MutableList<String> = mutableListOf()
    public val deleted: MutableList<String> = mutableListOf()
    public var seedCalls: Int = 0

    public val hidden: MutableStateFlow<List<HiddenTaxonomy>> = MutableStateFlow(emptyList())

    public var restoreResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)
    public var purgeResult: TaxonomyResult<Unit> = TaxonomyResult.Success(Unit)

    public val restored: MutableList<String> = mutableListOf()

    /**
     * Every id destroyed. No target beside it, matching the port: hiding a
     * payment method already scrubbed it off every entry, so a purge has
     * nothing to re-assign.
     */
    public val purged: MutableList<String> = mutableListOf()

    override fun observeAll(): Flow<List<PaymentMethod>> = methods

    override suspend fun find(id: String): PaymentMethod? = methods.value.firstOrNull { it.id == id }

    override suspend fun create(request: NewPaymentMethod): TaxonomyResult<PaymentMethod> {
        created += request
        return createResult ?: TaxonomyResult.Success(
            PaymentMethod(
                id = "generated-${created.size}",
                type = request.type,
                label = request.label,
                issuer = request.issuer,
                last4 = request.last4,
                colorArgb = request.colorArgb,
                isDefault = request.isDefault,
            ),
        )
    }

    override suspend fun update(method: PaymentMethod): TaxonomyResult<Unit> =
        TaxonomyResult.Success(Unit)

    override suspend fun setDefault(id: String): TaxonomyResult<Unit> {
        defaulted += id
        return TaxonomyResult.Success(Unit)
    }

    override suspend fun delete(id: String): TaxonomyResult<Unit> {
        deleted += id
        return TaxonomyResult.Success(Unit)
    }

    override fun observeHidden(): Flow<List<HiddenTaxonomy>> = hidden

    override suspend fun restore(id: String): TaxonomyResult<Unit> {
        restored += id
        return restoreResult
    }

    override suspend fun purge(id: String): TaxonomyResult<Unit> {
        purged += id
        return purgeResult
    }

    override suspend fun seedSystemDefaults(): Int {
        seedCalls++
        return 1
    }
}

/** Convenience for building fixture rows without repeating every field. */
public fun fakePaymentMethod(
    id: String,
    label: String,
    type: PaymentMethodType = PaymentMethodType.CASH,
    isDefault: Boolean = false,
    last4: String? = null,
): PaymentMethod = PaymentMethod(id, type, label, null, last4, null, isDefault)
