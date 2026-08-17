package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The taxonomy layer against a real SQLCipher database.
 *
 * Not unit tests with a fake DAO: the interesting behaviour here *is* the
 * database -- the `(parent_key, name, ledger_scope, deleted_at)` uniqueness
 * index, the transaction around re-assignment, and the two-level tree that no
 * SQLite constraint can express. A fake would assert the code we wrote rather
 * than the schema it has to satisfy.
 */
@RunWith(AndroidJUnit4::class)
class TaxonomyRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystoreAlias = "lf_taxonomy_test"
    private lateinit var keyDirectory: File

    private lateinit var session: VaultSession
    private lateinit var categories: DefaultCategoryRepository
    private lateinit var merchants: DefaultMerchantRepository
    private lateinit var paymentMethods: DefaultPaymentMethodRepository

    private var now = 1_000L
    private val clock = Clock { now }

    @Before
    fun setUp() = runBlocking {
        keyDirectory = File(context.filesDir, "keys-taxonomy-test").apply { deleteRecursively() }
        deleteKeystoreEntry()
        context.deleteDatabase(LedgerFlowDatabase.DATABASE_NAME)

        val store = FileWrappedDekStore(keyDirectory)
        val dekManager = DekManager(store, AndroidKeystoreKek(keystoreAlias), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO)
        session.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), "INR"))

        val ids = Uuid7Generator(SecureRandom())
        categories = DefaultCategoryRepository(session, ids, clock, Dispatchers.IO)
        merchants = DefaultMerchantRepository(session, ids, clock, Dispatchers.IO)
        paymentMethods = DefaultPaymentMethodRepository(session, ids, clock, Dispatchers.IO)
    }

    /**
     * Closing the database is not tidiness -- it is what keeps this suite from
     * crashing the instrumentation process.
     *
     * Every test opens a SQLCipher database, and each one holds a native
     * connection pool. Left open across ~30 tests in one process they
     * accumulate, and the run dies partway with a bare "Process crashed" and an
     * empty failure element. That surfaced here exactly once before being
     * tracked down, which is how it earns this comment: the symptom looks like
     * flake and the cause is a leak.
     */
    @After
    fun tearDown() {
        runCatching { session.requireDatabase().close() }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(LedgerFlowDatabase.DATABASE_NAME)
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias)
        }
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private fun TaxonomyResult<*>.error(): TaxonomyError {
        assertThat(this).isInstanceOf(TaxonomyResult.Failure::class.java)
        return (this as TaxonomyResult.Failure).error
    }

    // ── Seeding ─────────────────────────────────────────────────────────────

    @Test
    fun seed_populatesBothLedgersWithDisjointTrees() = runBlocking {
        categories.seedSystemDefaults()

        val debit = categories.observe(LedgerType.DEBIT).first()
        val credit = categories.observe(LedgerType.CREDIT).first()

        assertThat(debit).isNotEmpty()
        assertThat(credit).isNotEmpty()
        // Law 2: the two trees share no rows, not even by name coincidence of id.
        assertThat(debit.map { it.id }.intersect(credit.map { it.id }.toSet())).isEmpty()
        assertThat(debit.all { it.ledger == LedgerType.DEBIT }).isTrue()
        assertThat(credit.all { it.ledger == LedgerType.CREDIT }).isTrue()
    }

    /** Vault creation can be retried after a failure; seeding twice must not double up. */
    @Test
    fun seed_isIdempotent() = runBlocking {
        val first = categories.seedSystemDefaults()
        val second = categories.seedSystemDefaults()

        assertThat(first).isGreaterThan(0)
        assertThat(second).isEqualTo(0)
    }

    @Test
    fun seed_producesATwoLevelTreeWithNoOrphans() = runBlocking {
        categories.seedSystemDefaults()

        val tree = categories.observeTree(LedgerType.DEBIT).first()
        val all = categories.observe(LedgerType.DEBIT).first()

        assertThat(tree).isNotEmpty()
        // Every child in the tree has a parent that is itself top-level.
        tree.flatMap { it.children }.forEach { child ->
            val parent = all.single { it.id == child.parentId }
            assertThat(parent.isSubcategory).isFalse()
        }
        // Nothing is stranded: every row appears exactly once in the tree.
        assertThat(tree.size + tree.sumOf { it.children.size }).isEqualTo(all.size)
    }

    @Test
    fun seed_marksEverythingSystemButLeavesItRenameable() = runBlocking {
        categories.seedSystemDefaults()
        val food = categories.observe(LedgerType.DEBIT).first().first { it.name == "Groceries" }

        assertThat(food.isSystem).isTrue()
        categories.rename(food.id, "Supermarket").success()

        assertThat(categories.find(food.id)?.name).isEqualTo("Supermarket")
    }

    @Test
    fun seed_systemCategoriesCannotBeDeleted() = runBlocking {
        categories.seedSystemDefaults()
        val other = categories.observe(LedgerType.DEBIT).first().first { it.name == "Other" }

        assertThat(categories.delete(other.id, null).error())
            .isEqualTo(TaxonomyError.SystemProtected)
    }

    // ── Uniqueness and the parent_key sentinel (§6.1.1) ──────────────────────

    /**
     * The constraint SPEC.md §6.1.1 exists for. Two live top-level categories
     * with the same name must collide -- and would not if `parent_key` were left
     * as a nullable `parent_id`, because SQLite treats NULLs as distinct.
     */
    @Test
    fun duplicateTopLevelNameIsRefused() = runBlocking {
        categories.create(NewCategory(LedgerType.DEBIT, "Coffee")).success()

        assertThat(categories.create(NewCategory(LedgerType.DEBIT, "Coffee")).error())
            .isEqualTo(TaxonomyError.DuplicateName("Coffee"))
    }

    @Test
    fun duplicateNameIsCaseInsensitive() = runBlocking {
        categories.create(NewCategory(LedgerType.DEBIT, "Coffee")).success()

        assertThat(categories.create(NewCategory(LedgerType.DEBIT, "coffee")).error())
            .isInstanceOf(TaxonomyError.DuplicateName::class.java)
    }

    /** The trees are disjoint, so the same name in the other book is fine. */
    @Test
    fun theSameNameIsAllowedInTheOtherLedger() = runBlocking<Unit> {
        categories.create(NewCategory(LedgerType.DEBIT, "Interest")).success()
        categories.create(NewCategory(LedgerType.CREDIT, "Interest")).success()
    }

    @Test
    fun theSameNameIsAllowedUnderDifferentParents() = runBlocking<Unit> {
        val a = categories.create(NewCategory(LedgerType.DEBIT, "Travel")).success()
        val b = categories.create(NewCategory(LedgerType.DEBIT, "Work")).success()

        categories.create(NewCategory(LedgerType.DEBIT, "Taxi", parentId = a.id)).success()
        categories.create(NewCategory(LedgerType.DEBIT, "Taxi", parentId = b.id)).success()
    }

    /**
     * `parent_key` must equal `COALESCE(parent_id, '')` for **every** row, or
     * the uniqueness index quietly stops matching anything. Asserted directly
     * against the stored rows, as §6.1.1 requires.
     */
    @Test
    fun parentKeyAlwaysMirrorsParentId() = runBlocking {
        categories.seedSystemDefaults()
        val parent = categories.create(NewCategory(LedgerType.DEBIT, "Hobbies")).success()
        val child = categories.create(
            NewCategory(LedgerType.DEBIT, "Cycling", parentId = parent.id),
        ).success()
        categories.delete(child.id, null).success()

        val rows = session.requireDatabase().categoryDao().all()
        assertThat(rows).isNotEmpty()
        rows.forEach { row ->
            assertThat(row.parentKey).isEqualTo(row.parentId ?: "")
        }
    }

    // ── Two-level tree ───────────────────────────────────────────────────────

    @Test
    fun aSubcategoryCannotHaveASubcategory() = runBlocking {
        val parent = categories.create(NewCategory(LedgerType.DEBIT, "Transport")).success()
        val child = categories.create(
            NewCategory(LedgerType.DEBIT, "Fuel", parentId = parent.id),
        ).success()

        assertThat(categories.create(NewCategory(LedgerType.DEBIT, "Petrol", parentId = child.id)).error())
            .isEqualTo(TaxonomyError.InvalidParent)
    }

    @Test
    fun aParentFromTheOtherLedgerIsRefused() = runBlocking {
        val credit = categories.create(NewCategory(LedgerType.CREDIT, "Salary")).success()

        assertThat(categories.create(NewCategory(LedgerType.DEBIT, "Bonus", parentId = credit.id)).error())
            .isEqualTo(TaxonomyError.InvalidParent)
    }

    @Test
    fun blankNamesAreRefused() = runBlocking {
        assertThat(categories.create(NewCategory(LedgerType.DEBIT, "   ")).error())
            .isEqualTo(TaxonomyError.BlankName)
    }

    // ── Deletion and re-assignment ───────────────────────────────────────────

    private suspend fun seedEntry(ledger: LedgerType, categoryId: String, merchantId: String? = null) {
        session.requireDatabase().ledgerEntryDao().insertEntry(
            LedgerEntryEntity(
                id = Uuid7Generator(SecureRandom()).generate(),
                ledger = ledger,
                amountMinor = Money(1_000L),
                currency = "INR",
                originalAmountMinor = null,
                originalCurrency = null,
                fxRateMicro = null,
                occurredAt = now,
                localDate = 1,
                merchantId = merchantId,
                categoryId = categoryId,
                subcategoryId = null,
                paymentMethodId = null,
                note = null,
                source = EntrySource.MANUAL,
                sourceRefId = null,
                isRecurring = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    @Test
    fun deletingACategoryInUseWithoutATargetIsRefusedAndSaysHowMany() = runBlocking {
        val category = categories.create(NewCategory(LedgerType.DEBIT, "Snacks")).success()
        seedEntry(LedgerType.DEBIT, category.id)
        seedEntry(LedgerType.DEBIT, category.id)

        assertThat(categories.delete(category.id, null).error())
            .isEqualTo(TaxonomyError.ReassignRequired(2))
        // Refused means refused: the category is still there.
        assertThat(categories.find(category.id)).isNotNull()
    }

    @Test
    fun deletingWithATargetMovesTheEntriesAndKeepsTheCount() = runBlocking {
        val from = categories.create(NewCategory(LedgerType.DEBIT, "Snacks")).success()
        val to = categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        seedEntry(LedgerType.DEBIT, from.id)
        seedEntry(LedgerType.DEBIT, from.id)

        categories.delete(from.id, to.id).success()

        val entries = session.requireDatabase().ledgerEntryDao()
        assertThat(entries.countForCategory(LedgerType.DEBIT, from.id)).isEqualTo(0)
        assertThat(entries.countForCategory(LedgerType.DEBIT, to.id)).isEqualTo(2)
        assertThat(categories.find(from.id)).isNull()
    }

    @Test
    fun deletingAParentMovesItsChildrenRatherThanOrphaningThem() = runBlocking<Unit> {
        val from = categories.create(NewCategory(LedgerType.DEBIT, "Eating out")).success()
        val to = categories.create(NewCategory(LedgerType.DEBIT, "Food")).success()
        categories.create(NewCategory(LedgerType.DEBIT, "Cafes", parentId = from.id)).success()

        categories.delete(from.id, to.id).success()

        val tree = categories.observeTree(LedgerType.DEBIT).first()
        assertThat(tree.single { it.parent.id == to.id }.children.map { it.name })
            .containsExactly("Cafes")
    }

    @Test
    fun deletedCategoriesDisappearFromReadsButTheRowSurvives() = runBlocking {
        val category = categories.create(NewCategory(LedgerType.DEBIT, "Temporary")).success()
        categories.delete(category.id, null).success()

        assertThat(categories.observe(LedgerType.DEBIT).first().map { it.id })
            .doesNotContain(category.id)
        // Soft delete: nothing in this app hard-deletes user data (§5.5).
        assertThat(session.requireDatabase().categoryDao().byId(category.id)).isNotNull()
    }

    /** A soft-deleted name must be reusable, which is what `deleted_at` in the index buys. */
    @Test
    fun aDeletedNameCanBeUsedAgain() = runBlocking {
        val first = categories.create(NewCategory(LedgerType.DEBIT, "Gym")).success()
        now += 1
        categories.delete(first.id, null).success()

        val second = categories.create(NewCategory(LedgerType.DEBIT, "Gym")).success()
        assertThat(second.id).isNotEqualTo(first.id)
    }

    // ── Merchants ────────────────────────────────────────────────────────────

    @Test
    fun merchantVariantsResolveToOneRow() = runBlocking {
        val a = merchants.createOrGet("SWIGGY*ORDER4821").success()
        val b = merchants.createOrGet("Swiggy").success()

        assertThat(b.id).isEqualTo(a.id)
        assertThat(merchants.observeAll().first()).hasSize(1)
    }

    @Test
    fun findByName_matchesThroughNormalization() = runBlocking {
        merchants.createOrGet("Reliance Fresh 1182").success()

        assertThat(merchants.findByName("RELIANCE FRESH")?.canonicalName)
            .isEqualTo("Reliance Fresh 1182")
    }

    /**
     * A merchant appears in both books -- a refund from a shop is a credit. The
     * merge must move both, which it does by iterating the partition rather than
     * writing one statement that reaches across it (ADR-0002).
     */
    @Test
    fun mergingMovesEntriesInBothLedgers() = runBlocking {
        val category = categories.create(NewCategory(LedgerType.DEBIT, "Shopping")).success()
        val creditCategory = categories.create(NewCategory(LedgerType.CREDIT, "Refunds")).success()
        val source = merchants.createOrGet("Amazn").success()
        val target = merchants.createOrGet("Amazon").success()
        seedEntry(LedgerType.DEBIT, category.id, merchantId = source.id)
        seedEntry(LedgerType.CREDIT, creditCategory.id, merchantId = source.id)

        merchants.merge(source.id, target.id).success()

        val entries = session.requireDatabase().ledgerEntryDao()
        assertThat(entries.countForMerchant(LedgerType.DEBIT, target.id)).isEqualTo(1)
        assertThat(entries.countForMerchant(LedgerType.CREDIT, target.id)).isEqualTo(1)
        assertThat(entries.countForMerchant(LedgerType.DEBIT, source.id)).isEqualTo(0)
        assertThat(merchants.find(source.id)).isNull()
    }

    @Test
    fun mergingAMerchantIntoItselfIsRefused() = runBlocking {
        val merchant = merchants.createOrGet("Zomato").success()

        assertThat(merchants.merge(merchant.id, merchant.id).error())
            .isEqualTo(TaxonomyError.SameSourceAndTarget)
    }

    @Test
    fun renamingOntoAnotherMerchantsKeyIsRefused() = runBlocking {
        merchants.createOrGet("Zomato").success()
        val other = merchants.createOrGet("Swiggy").success()

        assertThat(merchants.rename(other.id, "ZOMATO Ltd").error())
            .isInstanceOf(TaxonomyError.DuplicateName::class.java)
    }

    // ── Payment methods ──────────────────────────────────────────────────────

    @Test
    fun seed_startsWithCashAsTheDefault() = runBlocking {
        paymentMethods.seedSystemDefaults()

        val all = paymentMethods.observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(all.single().type).isEqualTo(PaymentMethodType.CASH)
        assertThat(all.single().isDefault).isTrue()
    }

    /** Two defaults would make "which card was that?" depend on row order. */
    @Test
    fun exactlyOneMethodIsEverTheDefault() = runBlocking {
        paymentMethods.seedSystemDefaults()
        val card = paymentMethods.create(
            NewPaymentMethod(PaymentMethodType.CREDIT_CARD, "HDFC Card", last4 = "4821"),
        ).success()

        paymentMethods.setDefault(card.id).success()

        val all = paymentMethods.observeAll().first()
        assertThat(all.filter { it.isDefault }).hasSize(1)
        assertThat(all.single { it.isDefault }.id).isEqualTo(card.id)
    }

    /** §5.5 stores a last-4, never a card number. A longer value is truncated, not kept. */
    @Test
    fun onlyTheLastFourDigitsAreStored() = runBlocking {
        val card = paymentMethods.create(
            NewPaymentMethod(PaymentMethodType.CREDIT_CARD, "Visa", last4 = "4111111111111234"),
        ).success()

        assertThat(paymentMethods.find(card.id)?.last4).isEqualTo("1234")
    }

    @Test
    fun deletingAMethodDetachesItFromEntriesRatherThanDeletingThem() = runBlocking {
        val category = categories.create(NewCategory(LedgerType.DEBIT, "Fuel")).success()
        val card = paymentMethods.create(
            NewPaymentMethod(PaymentMethodType.CREDIT_CARD, "Amex", last4 = "0005"),
        ).success()
        val entries = session.requireDatabase().ledgerEntryDao()
        seedEntry(LedgerType.DEBIT, category.id)
        val entryId = entries.allForLedger(LedgerType.DEBIT).single().id
        entries.insertEntry(
            LedgerEntryEntity(
                id = Uuid7Generator(SecureRandom()).generate(),
                ledger = LedgerType.DEBIT,
                amountMinor = Money(500L),
                currency = "INR",
                originalAmountMinor = null,
                originalCurrency = null,
                fxRateMicro = null,
                occurredAt = now,
                localDate = 1,
                merchantId = null,
                categoryId = category.id,
                subcategoryId = null,
                paymentMethodId = card.id,
                note = null,
                source = EntrySource.MANUAL,
                sourceRefId = null,
                isRecurring = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        paymentMethods.delete(card.id).success()

        assertThat(entries.countForLedger(LedgerType.DEBIT)).isEqualTo(2)
        assertThat(entries.allForLedger(LedgerType.DEBIT).all { it.paymentMethodId == null })
            .isTrue()
        assertThat(entryId).isNotEmpty()
    }

    @Test
    fun duplicateMethodLabelsAreRefused() = runBlocking {
        paymentMethods.create(NewPaymentMethod(PaymentMethodType.UPI, "GPay")).success()

        assertThat(paymentMethods.create(NewPaymentMethod(PaymentMethodType.UPI, "GPay")).error())
            .isEqualTo(TaxonomyError.DuplicateName("GPay"))
    }
}
