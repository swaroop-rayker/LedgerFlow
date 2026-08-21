package com.ledgerflow.core.data.taxonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG12 — deleting a category promoted its subcategories to top-level
 * categories the user never created.
 *
 * `DefaultCategoryRepository.delete` called
 * `reparentChildren(id, reassignTo, parentKeyOf(reassignTo))`, and in the
 * no-entries path `reassignTo` is null — so the children's `parent_id` became
 * null and they reappeared at the top of the tree, unrelated to anything, named
 * for a parent that was gone. The comment directly above that call had always
 * claimed the opposite: "Children follow the parent out rather than becoming
 * orphaned top-level categories the user never created."
 *
 * It was cosmetic while a delete was one-way. ADR-0016 makes it load-bearing:
 * with a hidden list and a restore, a branch that goes out in pieces comes back
 * in pieces — the parent alone, with its subcategories still sitting at the top
 * level where the delete left them. A restore that returns a different tree from
 * the one that was deleted is not a restore.
 *
 * The fix is `softDeleteChildren`, which stamps the branch with **one**
 * `deleted_at`. That shared timestamp is the only record in the schema of which
 * deletion a hidden child belonged to, and both `restore` and `purge` match on
 * it.
 */
@RunWith(AndroidJUnit4::class)
class Bug12_HiddenCategoryKeepsItsChildrenTest {

    private val vault = LedgerTestVault("lf_bug12_test")

    private lateinit var food: Category
    private lateinit var groceries: Category
    private lateinit var dining: Category
    private lateinit var transport: Category

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        food = vault.categories.create(NewCategory(LedgerType.DEBIT, "Food")).success()
        groceries = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Groceries", parentId = food.id))
            .success()
        dining = vault.categories
            .create(NewCategory(LedgerType.DEBIT, "Dining", parentId = food.id))
            .success()
        transport = vault.categories.create(NewCategory(LedgerType.DEBIT, "Transport")).success()
    }

    @After
    fun tearDown() = vault.close()

    /** The regression itself: no orphans at the top of the tree. */
    @Test
    fun deletingAParent_doesNotPromoteItsSubcategories() = runBlocking<Unit> {
        vault.categories.delete(food.id, null).success()

        val topLevel = vault.categories.observeTree(LedgerType.DEBIT).first().map { it.parent.name }
        assertThat(topLevel).containsExactly("Transport")
    }

    @Test
    fun deletingAParent_hidesItsSubcategoriesWithIt() = runBlocking<Unit> {
        vault.categories.delete(food.id, null).success()

        assertThat(liveNames()).containsExactly("Transport")
        // One row for the branch, not three -- it went out as a unit.
        val hidden = vault.categories.observeHidden(LedgerType.DEBIT).first()
        assertThat(hidden.map { it.name }).containsExactly("Food")
        assertThat(hidden.single().detail).isEqualTo("with 2 subcategories")
    }

    @Test
    fun restoringAParent_bringsTheTreeBackTheShapeItLeftIn() = runBlocking<Unit> {
        vault.categories.delete(food.id, null).success()

        vault.categories.restore(food.id).success()

        val tree = vault.categories.observeTree(LedgerType.DEBIT).first()
        val branch = tree.single { it.parent.name == "Food" }
        assertThat(branch.children.map { it.name }).containsExactly("Groceries", "Dining")
        assertThat(vault.categories.observeHidden(LedgerType.DEBIT).first()).isEmpty()
    }

    /**
     * The re-assign path hides the branch too.
     *
     * The old code's *other* half: with a non-null target the children were
     * reparented under the category the entries moved to, silently adopting them
     * into a tree they never belonged to. Entries move; subcategories do not.
     */
    @Test
    fun deletingAParentWithEntries_hidesTheBranchRatherThanReparentingIt() = runBlocking<Unit> {
        val entry = approve(categoryId = food.id)

        vault.categories.delete(food.id, transport.id).success()

        val tree = vault.categories.observeTree(LedgerType.DEBIT).first()
        val remaining = tree.single()
        assertThat(remaining.parent.name).isEqualTo("Transport")
        // The entries moved. The subcategories did not come with them.
        assertThat(remaining.children).isEmpty()
        assertThat(rowFor(entry.id).categoryId).isEqualTo(transport.id)
    }

    /**
     * A subcategory restored on its own brings its parent back with it.
     *
     * `observeTree` builds children off live parents, so a live subcategory
     * under a hidden parent is not a layout problem — it is a row that exists,
     * can still be pointed at, and appears on no screen in the app.
     */
    @Test
    fun restoringASubcategoryAlone_bringsItsHiddenParentBack() = runBlocking<Unit> {
        vault.categories.delete(food.id, null).success()

        vault.categories.restore(groceries.id).success()

        val tree = vault.categories.observeTree(LedgerType.DEBIT).first()
        val branch = tree.single { it.parent.name == "Food" }
        assertThat(branch.children.map { it.name }).containsExactly("Groceries")
        // Dining stays hidden: it was not what was asked for.
        assertThat(vault.categories.observeHidden(LedgerType.DEBIT).first().map { it.name })
            .containsExactly("Dining")
    }

    /** Deleting a subcategory on its own still leaves its parent alone. */
    @Test
    fun deletingASubcategory_leavesTheParentAndItsSiblings() = runBlocking<Unit> {
        vault.categories.delete(dining.id, null).success()

        val tree = vault.categories.observeTree(LedgerType.DEBIT).first()
        val branch = tree.single { it.parent.name == "Food" }
        assertThat(branch.children.map { it.name }).containsExactly("Groceries")

        val hidden = vault.categories.observeHidden(LedgerType.DEBIT).first().single()
        assertThat(hidden.name).isEqualTo("Dining")
        // Named by the parent it belongs under -- the fact that tells "Groceries"
        // hidden from Food apart from "Groceries" hidden from Household.
        assertThat(hidden.detail).isEqualTo("under Food")
    }

    private suspend fun liveNames() =
        vault.categories.observe(LedgerType.DEBIT).first().map { it.name }

    private suspend fun approve(categoryId: String): LedgerEntry = vault.ledger.approve(
        ApprovalRequest(
            ledger = LedgerType.DEBIT,
            amount = Money(100_00L),
            occurredAt = OCCURRED_AT,
            assignment = EntryAssignment(categoryId = categoryId),
        ),
    ).success()

    private suspend fun rowFor(id: String) = vault.session.requireDatabase()
        .ledgerEntryDao().allForLedger(LedgerType.DEBIT).single { it.id == id }

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private companion object {
        private const val OCCURRED_AT = 1_700_000_000_000L
    }
}
