package com.ledgerflow.core.testing.taxonomy

import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory taxonomy fakes.
 *
 * Deliberately shallow: the substantive behaviour -- the uniqueness index, the
 * two-level tree, transactional re-assignment -- is a property of the *schema*,
 * so it is tested instrumented against a real SQLCipher database in
 * `TaxonomyRepositoryInstrumentedTest`. Reimplementing those rules here would
 * produce a fake that passes tests the real one fails.
 *
 * What these are for is the callers: asserting that onboarding seeds exactly
 * once, that a screen asks for the right ledger, and so on.
 */
public class FakeCategoryRepository : CategoryRepository {

    public val categories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
    public var seedCalls: Int = 0
    public var seedCount: Int = 1

    override fun observe(ledger: LedgerType): Flow<List<Category>> = categories

    override fun observeTree(ledger: LedgerType): Flow<List<CategoryTree>> =
        MutableStateFlow(emptyList())

    override suspend fun find(id: String): Category? = categories.value.firstOrNull { it.id == id }

    override suspend fun create(request: NewCategory): TaxonomyResult<Category> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun rename(id: String, name: String): TaxonomyResult<Unit> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun updateAppearance(
        id: String,
        icon: String,
        colorArgb: Int,
    ): TaxonomyResult<Unit> = TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun delete(id: String, reassignTo: String?): TaxonomyResult<Unit> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun seedSystemDefaults(): Int {
        seedCalls++
        return seedCount
    }
}

public class FakePaymentMethodRepository : PaymentMethodRepository {

    public val methods: MutableStateFlow<List<PaymentMethod>> = MutableStateFlow(emptyList())
    public var seedCalls: Int = 0

    override fun observeAll(): Flow<List<PaymentMethod>> = methods

    override suspend fun find(id: String): PaymentMethod? = methods.value.firstOrNull { it.id == id }

    override suspend fun create(request: NewPaymentMethod): TaxonomyResult<PaymentMethod> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun update(method: PaymentMethod): TaxonomyResult<Unit> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun setDefault(id: String): TaxonomyResult<Unit> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun delete(id: String): TaxonomyResult<Unit> =
        TaxonomyResult.Failure(TaxonomyError.NotFound)

    override suspend fun seedSystemDefaults(): Int {
        seedCalls++
        return 1
    }
}
