package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
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

// ── The hidden taxonomy: restore, and the three audited destroys (ADR-0016) ──

/**
 * What has been hidden in one book, for the section that offers it back.
 *
 * Three flows rather than one merged list, because the three are shown in three
 * places -- each section's hidden rows sit under that section's live ones. The
 * bin merges two books into one list (ADR-0015) because a deleted entry is
 * looked for without knowing which book it was in; a hidden merchant is looked
 * for on the Merchants tab, by someone who is already there.
 */
public class ObserveHiddenTaxonomyUseCase @Inject constructor(
    private val categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val paymentMethods: PaymentMethodRepository,
) {
    public fun categories(ledger: LedgerType): Flow<List<HiddenTaxonomy>> =
        categories.observeHidden(ledger)

    public fun merchants(): Flow<List<HiddenTaxonomy>> = merchants.observeHidden()

    public fun paymentMethods(): Flow<List<HiddenTaxonomy>> = paymentMethods.observeHidden()
}

/**
 * Puts hidden taxonomy back.
 *
 * **Not guarded the way the purges below are, and the asymmetry is deliberate.**
 * `LedgerSingleWriterTest` guards `restoreEntry` because un-binning an entry
 * changes totals somebody has already read. Un-hiding a merchant changes what a
 * picker offers and no figure anywhere, so a guard here would be ceremony --
 * and ceremony is what teaches people that the guards are ceremony.
 *
 * It exists as a use case at all only so the three restores have one name; the
 * repositories remain reachable directly, as they are for rename and merge.
 */
public class RestoreHiddenTaxonomyUseCase @Inject constructor(
    private val categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val paymentMethods: PaymentMethodRepository,
) {
    public suspend fun category(id: String): TaxonomyResult<Unit> = categories.restore(id)

    public suspend fun merchant(id: String): TaxonomyResult<Unit> = merchants.restore(id)

    public suspend fun paymentMethod(id: String): TaxonomyResult<Unit> =
        paymentMethods.restore(id)
}

/**
 * **Destroys a hidden category. Irreversible.**
 *
 * The single audited door onto `CategoryDao.hardDelete`, on the same principle
 * as `PurgeDeletedEntriesUseCase`: `TaxonomySingleWriterTest` fails the build on
 * any other caller.
 *
 * The door matters more here than the name suggests. `ledger_entry.category_id`
 * has **no foreign key**, so SQLite will destroy the row and leave every entry
 * that used it holding an id resolving to nothing -- reporting success. The
 * refusal that prevents that lives in the repository, and this is the only way
 * to reach past it.
 *
 * Compaction is the repository's job rather than this one's, unlike the ledger
 * purge: a category may take entries' re-pointing with it, and that has to
 * happen inside the same transaction as the destroy. Splitting the two across
 * layers is what would let a crash land between them.
 *
 * @param reassignTo where the entries go. Omitted first: the refusal comes back
 *   with the count, so the caller asks the user rather than guessing.
 */
public class PurgeHiddenCategoryUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    public suspend operator fun invoke(
        id: String,
        reassignTo: String? = null,
    ): TaxonomyResult<Unit> = categories.purge(id, reassignTo)
}

/**
 * **Destroys a hidden merchant. Irreversible.**
 *
 * The single audited door onto `MerchantDao.hardDelete`.
 *
 * `ledger_entry.merchant_id` is `ON DELETE SET NULL`, which is worse than no key
 * at all for this purpose: the destroy succeeds, every entry keeps its amount,
 * and the shop's name is gone from all of them with nothing raised. The
 * reference count in the repository is the only thing standing in front of that.
 */
public class PurgeHiddenMerchantUseCase @Inject constructor(
    private val merchants: MerchantRepository,
) {
    public suspend operator fun invoke(
        id: String,
        reassignTo: String? = null,
    ): TaxonomyResult<Unit> = merchants.purge(id, reassignTo)
}

/**
 * **Destroys a hidden payment method. Irreversible.**
 *
 * The single audited door onto `PaymentMethodDao.hardDelete`, and the only one
 * of the three that needs no re-assignment: hiding a payment method already
 * scrubbed `payment_method_id` off every entry in both books, binned ones
 * included, so by the time a row is hidden nothing points at it.
 */
public class PurgeHiddenPaymentMethodUseCase @Inject constructor(
    private val paymentMethods: PaymentMethodRepository,
) {
    public suspend operator fun invoke(id: String): TaxonomyResult<Unit> =
        paymentMethods.purge(id)
}
