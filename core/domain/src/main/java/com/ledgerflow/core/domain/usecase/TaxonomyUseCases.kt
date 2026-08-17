package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The category tree for one ledger. Never both (Law 2). */
public class ObserveCategoryTreeUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    public operator fun invoke(ledger: LedgerType): Flow<List<CategoryTree>> =
        categories.observeTree(ledger)
}

public class CreateCategoryUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    public suspend operator fun invoke(request: NewCategory): TaxonomyResult<Category> =
        categories.create(request)
}

/**
 * Soft-deletes a category, moving anything that pointed at it.
 *
 * Deliberately one call rather than "check, then delete": between a caller's
 * count query and its delete, an approval could add an entry, and the category
 * would disappear taking that entry's category with it. The repository does
 * both inside one transaction.
 */
public class DeleteCategoryUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    public suspend operator fun invoke(
        id: String,
        reassignTo: String? = null,
    ): TaxonomyResult<Unit> = categories.delete(id, reassignTo)
}

public class ObserveMerchantsUseCase @Inject constructor(
    private val merchants: MerchantRepository,
) {
    public operator fun invoke(): Flow<List<Merchant>> = merchants.observeAll()
}

public class MergeMerchantsUseCase @Inject constructor(
    private val merchants: MerchantRepository,
) {
    public suspend operator fun invoke(sourceId: String, targetId: String): TaxonomyResult<Unit> =
        merchants.merge(sourceId, targetId)
}

public class ObservePaymentMethodsUseCase @Inject constructor(
    private val paymentMethods: PaymentMethodRepository,
) {
    public operator fun invoke(): Flow<List<PaymentMethod>> = paymentMethods.observeAll()
}

public class CreatePaymentMethodUseCase @Inject constructor(
    private val paymentMethods: PaymentMethodRepository,
) {
    public suspend operator fun invoke(
        request: NewPaymentMethod,
    ): TaxonomyResult<PaymentMethod> = paymentMethods.create(request)
}

/**
 * Writes the shipped starter taxonomy.
 *
 * Called from [InitializeVaultUseCase] immediately after the vault is created,
 * because an app whose very first screen is an empty category picker asks the
 * user to do setup work before they can record a single expense. Everything it
 * writes is `is_system = 1` but fully editable (§5.5) -- shipped defaults, not
 * fixtures.
 */
public class SeedDefaultTaxonomyUseCase @Inject constructor(
    private val categories: CategoryRepository,
    private val paymentMethods: PaymentMethodRepository,
) {
    public suspend operator fun invoke(): Int =
        categories.seedSystemDefaults() + paymentMethods.seedSystemDefaults()
}
