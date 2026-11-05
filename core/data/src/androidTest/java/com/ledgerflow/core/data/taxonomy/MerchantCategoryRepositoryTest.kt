package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Merchant categories against a real encrypted vault (V2-PLAN §5, V2-6).
 */
@RunWith(AndroidJUnit4::class)
class MerchantCategoryRepositoryTest {

    private val vault = LedgerTestVault("lf_merchant_category_test")
    private lateinit var repository: DefaultMerchantCategoryRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        repository = DefaultMerchantCategoryRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
    }

    @After
    fun tearDown() = vault.close()

    private fun <T> TaxonomyResult<T>.value(): T = (this as TaxonomyResult.Success).value

    /** The owner's twelve (§5.2), in order, once; no merchant filed under any. */
    @Test
    fun theDefaults_areSeededOnce_inTheOwnersOrder() = runBlocking<Unit> {
        val zepto = vault.merchants.createOrGet("Zepto").value()

        assertThat(repository.seedDefaultsOnce()).isEqualTo(12)
        assertThat(repository.seedDefaultsOnce()).isEqualTo(0)

        val names = repository.observeAll().first().map { it.name }
        assertThat(names).containsExactlyElementsIn(DefaultMerchantCategories.names).inOrder()
        assertThat(vault.merchants.find(zepto.id)?.merchantCategoryId).isNull()
    }

    /** A user who deletes every default is not refilled at the next launch. */
    @Test
    fun deletingTheDefaults_doesNotBringThemBack() = runBlocking<Unit> {
        repository.seedDefaultsOnce()
        repository.observeAll().first().forEach { repository.delete(it.id, moveTo = null) }

        assertThat(repository.seedDefaultsOnce()).isEqualTo(0)
        assertThat(repository.observeAll().first()).isEmpty()
        assertThat(vault.database.appMetaDao().value(AppMetaEntity.KEY_MERCHANT_CATEGORIES_SEEDED)).isNotNull()
    }

    @Test
    fun setCategory_filesMany_andNullIsUncategorised() = runBlocking<Unit> {
        val fuel = repository.create("Fuel").value()
        val a = vault.merchants.createOrGet("Indian Oil").value()
        val b = vault.merchants.createOrGet("HP Petrol").value()

        assertThat(repository.setCategory(listOf(a.id, b.id), fuel.id).value()).isEqualTo(2)
        assertThat(vault.merchants.find(a.id)?.merchantCategoryId).isEqualTo(fuel.id)

        repository.setCategory(listOf(a.id), null)
        assertThat(vault.merchants.find(a.id)?.merchantCategoryId).isNull()
        assertThat(vault.merchants.find(b.id)?.merchantCategoryId).isEqualTo(fuel.id)
    }

    /** §5.1: deleting asks where its merchants go, another category or Uncategorised. */
    @Test
    fun delete_movesItsMerchants_toAnotherOrToUncategorised() = runBlocking<Unit> {
        val fuel = repository.create("Fuel").value()
        val travel = repository.create("Travel & transport").value()
        val shell = vault.merchants.createOrGet("Shell").value()
        val uber = vault.merchants.createOrGet("Uber").value()
        repository.setCategory(listOf(shell.id), fuel.id)
        repository.setCategory(listOf(uber.id), travel.id)

        assertThat(repository.delete(fuel.id, moveTo = travel.id).value()).isEqualTo(1)
        assertThat(vault.merchants.find(shell.id)?.merchantCategoryId).isEqualTo(travel.id)

        assertThat(repository.delete(travel.id, moveTo = null).value()).isEqualTo(2)
        assertThat(vault.merchants.find(shell.id)?.merchantCategoryId).isNull()
        assertThat(vault.merchants.find(uber.id)?.merchantCategoryId).isNull()
        assertThat(repository.observeHidden().first().map { it.name }).containsExactly("Travel & transport", "Fuel")
    }

    /** Names are unique among live categories, ignoring case; a hidden one can be reused and then collides on restore. */
    @Test
    fun names_areUniqueAmongLiveCategories() = runBlocking<Unit> {
        val fuel = repository.create("Fuel").value()

        assertThat(repository.create("fuel")).isEqualTo(TaxonomyResult.Failure(TaxonomyError.DuplicateName("fuel")))
        assertThat(repository.create("  ")).isEqualTo(TaxonomyResult.Failure(TaxonomyError.BlankName))

        repository.delete(fuel.id, moveTo = null)
        repository.create("Fuel").value()
        assertThat(repository.restore(fuel.id)).isEqualTo(TaxonomyResult.Failure(TaxonomyError.DuplicateName("Fuel")))
    }

    /** A merge keeps the survivor's category (V2-PLAN §5.1). */
    @Test
    fun merge_keepsTheSurvivorsCategory() = runBlocking<Unit> {
        val groceries = repository.create("Groceries").value()
        val services = repository.create("Services").value()
        val survivor = vault.merchants.createOrGet("Big Bazaar").value()
        val folded = vault.merchants.createOrGet("BigBazaar Online").value()
        repository.setCategory(listOf(survivor.id), groceries.id)
        repository.setCategory(listOf(folded.id), services.id)

        vault.merchants.merge(sourceId = folded.id, targetId = survivor.id)

        assertThat(vault.merchants.find(survivor.id)?.merchantCategoryId).isEqualTo(groceries.id)
    }
}
