package com.ledgerflow

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.ledger.DefaultDraftRepository
import com.ledgerflow.core.data.ledger.DefaultLedgerRepository
import com.ledgerflow.core.data.taxonomy.DefaultCategoryRepository
import com.ledgerflow.core.data.taxonomy.DefaultMerchantRepository
import com.ledgerflow.core.data.taxonomy.DefaultPaymentMethodRepository
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.feature.entry.EntryEvent
import com.ledgerflow.feature.entry.EntryPicker
import com.ledgerflow.feature.entry.EntryViewModel
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG6 — expense data vanishing before it is saved (SPEC.md §8).
 *
 * The root cause was draft state living only in a ViewModel field, where a
 * process death, a config change or an interruption took it. The countermeasure
 * is `draft_entry` (§6.1.2): every field change persists to Room behind a 300 ms
 * debounce.
 *
 * **What "kill the process" means here.** An instrumented test cannot literally
 * kill the process it is running in -- `Process.killProcess(myPid())` takes the
 * test with it and reports nothing. What actually has to be proven is that
 * *nothing in memory carried the data*, and that is provable without a kill: the
 * test clears the `ViewModelStore` (which cancels `viewModelScope` exactly as
 * teardown does), closes the SQLCipher database, drops every reference, and
 * then rebuilds the entire graph from disk -- a fresh `DekManager` unwrapping
 * the DEK from the Keystore again, a fresh database handle, a fresh ViewModel.
 * If the form comes back, it came back off disk, because there is nowhere else
 * left for it to have been.
 *
 * The real force-stop → relaunch cycle stays on the manual matrix in
 * `TESTING.md`, where the OS-level behaviour that no runner reproduces belongs.
 */
@RunWith(AndroidJUnit4::class)
class Bug6_DraftSurvivesProcessDeathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystoreAlias = "lf_bug6_test"
    private val keyDirectory = File(context.filesDir, "keys-bug6-test")

    private var session: VaultSession? = null
    private val collectors = mutableListOf<Job>()

    @Before
    fun setUp() = runBlocking<Unit> {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(LedgerFlowDatabase.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        // Every open vault holds a native SQLCipher connection pool; leaving one
        // behind is how a suite dies with a bare "Process crashed".
        runCatching { runBlocking { session?.close() } }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(LedgerFlowDatabase.DATABASE_NAME)
    }

    @Test
    fun draftSurvivesProcessDeathAndIsRestoredFieldForField() = runBlocking<Unit> {
        // ── Before ──────────────────────────────────────────────────────────
        val first = openGraph(create = true)
        val groceries = first.newCategory("Groceries")
        val vegetables = first.newCategory("Vegetables", parentId = groceries.id)

        val storeBefore = ViewModelStore()
        val before = first.entryViewModel()
        storeBefore.put(VIEW_MODEL_KEY, before)
        // stateIn(WhileSubscribed) emits nothing until collected, so without a
        // subscriber `state.value` is the seed and the reads below would be
        // asserting on a form nobody filled in.
        val beforeCollector = CoroutineScope(Dispatchers.Main).launch { before.state.collect {} }
        collectors += beforeCollector
        // Let the form finish opening -- it reads its slot on construction --
        // before typing, which is what a person does. The race the other way is
        // guarded in the ViewModel; driving it here would be testing the guard
        // rather than the persistence.
        delay(RESTORE_SETTLE_MS)

        before.onEvent(EntryEvent.DigitsPressed("1"))
        before.onEvent(EntryEvent.DigitsPressed("2"))
        before.onEvent(EntryEvent.DigitsPressed("5"))
        before.onEvent(EntryEvent.DigitsPressed("00"))
        before.select(EntryPicker.Category, groceries.id)
        before.select(EntryPicker.Subcategory(groceries.id), vegetables.id)
        before.onEvent(EntryEvent.NoteChanged("weekly shop, half typed"))
        before.onEvent(EntryEvent.LineItemAdded)

        // Real time, because the debounce is real time. Generous enough that a
        // slow device is not a failure, short enough to stay a unit of work.
        delay(DEBOUNCE_SETTLE_MS)

        val lineItemKey = before.state.value.lineItems.singleOrNull()?.key
        before.onEvent(EntryEvent.LineItemNameChanged(requireNotNull(lineItemKey), "Rice"))
        before.onEvent(EntryEvent.LineItemDigitsChanged(lineItemKey, "6000"))
        delay(DEBOUNCE_SETTLE_MS)

        val expected = before.state.value

        // ── The kill ────────────────────────────────────────────────────────
        // clear() invokes onCleared() and cancels viewModelScope, so the
        // debounce collector and every pending write are gone. Closing the
        // database drops the connection pool and the in-memory page cache. The
        // DEK goes with the DekManager. After this line nothing that held the
        // form is alive.
        beforeCollector.cancel()
        storeBefore.clear()
        first.close()
        session = null

        // ── After ───────────────────────────────────────────────────────────
        val second = openGraph(create = false)
        val after = second.entryViewModel()
        collectors += CoroutineScope(Dispatchers.Main).launch { after.state.collect {} }
        delay(RESTORE_SETTLE_MS)

        val restored = after.state.value

        assertThat(restored.amountMinor).isEqualTo(125_00L)
        assertThat(restored.amountMinor).isEqualTo(expected.amountMinor)
        assertThat(restored.categoryId).isEqualTo(groceries.id)
        assertThat(restored.subcategoryId).isEqualTo(vegetables.id)
        assertThat(restored.note).isEqualTo("weekly shop, half typed")
        assertThat(restored.ledger).isEqualTo(LedgerType.DEBIT)

        // Line items are part of the form, not a separate concern that gets to
        // be lost -- the multi-line editor is exactly where BUG6 hurts most.
        assertThat(restored.lineItems).hasSize(1)
        assertThat(restored.lineItems.single().name).isEqualTo("Rice")
        assertThat(restored.lineItems.single().amountMinor).isEqualTo(60_00L)
        assertThat(restored.lineItems.single().key).isEqualTo(lineItemKey)

        // And the user is told why yesterday's amount is on screen (§6.1.2).
        assertThat(restored.resumedFromDraft).isTrue()
    }

    /** Nothing typed, nothing stored: an untouched form must not create a draft. */
    @Test
    fun anUntouchedFormLeavesNothingToResume() = runBlocking<Unit> {
        val first = openGraph(create = true)
        val store = ViewModelStore()
        store.put(VIEW_MODEL_KEY, first.entryViewModel())
        delay(DEBOUNCE_SETTLE_MS)

        store.clear()
        first.close()
        session = null

        val second = openGraph(create = false)
        val after = second.entryViewModel()
        collectors += CoroutineScope(Dispatchers.Main).launch { after.state.collect {} }
        delay(RESTORE_SETTLE_MS)

        assertThat(after.state.value.amountMinor).isEqualTo(0L)
        assertThat(after.state.value.resumedFromDraft).isFalse()
    }

    // ── Graph assembly ──────────────────────────────────────────────────────

    /**
     * The whole object graph, built the way Hilt builds it.
     *
     * Constructed by hand rather than through `HiltAndroidRule` because the
     * point of the test is to build it *twice*, with a teardown in between, and
     * a Hilt component is a singleton scope that would quietly hand back the
     * same `VaultSession` the second time -- which would make the test pass for
     * the wrong reason.
     */
    private suspend fun openGraph(create: Boolean): Graph {
        val store = FileWrappedDekStore(keyDirectory)
        val dekManager = DekManager(store, AndroidKeystoreKek(keystoreAlias), SecureRandom())
        val vault = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO)

        if (create) {
            vault.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), "INR"))
        } else {
            vault.openOnLaunch()
        }
        session = vault
        return Graph(vault)
    }

    private inner class Graph(val vault: VaultSession) {
        private val ids = Uuid7Generator(SecureRandom())
        private val clock = Clock.System

        val categories = DefaultCategoryRepository(vault, ids, clock, Dispatchers.IO)

        fun entryViewModel() = EntryViewModel(
            approveTransaction = ApproveTransactionUseCase(
                DefaultLedgerRepository(vault, ids, clock, Dispatchers.IO),
            ),
            drafts = DefaultDraftRepository(vault, ids, clock, Dispatchers.IO),
            ledgerRepository = DefaultLedgerRepository(vault, ids, clock, Dispatchers.IO),
            categories = categories,
            merchants = DefaultMerchantRepository(vault, ids, clock, Dispatchers.IO),
            paymentMethods = DefaultPaymentMethodRepository(vault, ids, clock, Dispatchers.IO),
            clock = clock,
            ids = ids,
        )

        suspend fun newCategory(name: String, parentId: String? = null): Category {
            val result = categories.create(NewCategory(LedgerType.DEBIT, name, parentId = parentId))
            assertThat(result).isInstanceOf(TaxonomyResult.Success::class.java)
            return (result as TaxonomyResult.Success).value
        }

        suspend fun close() {
            vault.close()
        }
    }

    private fun EntryViewModel.select(picker: EntryPicker, id: String) {
        onEvent(EntryEvent.PickerOpened(picker))
        onEvent(EntryEvent.PickerItemSelected(id))
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias)
        }
    }

    private companion object {
        private const val VIEW_MODEL_KEY = "entry"

        /** Comfortably past the 300 ms debounce, without being a sleep-and-hope. */
        private const val DEBOUNCE_SETTLE_MS = 800L
        private const val RESTORE_SETTLE_MS = 600L
    }
}
