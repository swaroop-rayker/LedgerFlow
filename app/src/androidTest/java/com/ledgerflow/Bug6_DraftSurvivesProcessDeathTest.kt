package com.ledgerflow

import androidx.lifecycle.SavedStateHandle
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
import com.ledgerflow.core.data.vault.DefaultStorageMaintenance
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.feature.entry.EntryEvent
import com.ledgerflow.feature.entry.EntryPicker
import com.ledgerflow.feature.entry.EntryUiState
import com.ledgerflow.feature.entry.EntryViewModel
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    /**
     * Per test **method**, not per class.
     *
     * JUnit builds a fresh instance for each method, so this gives every method
     * its own database, key directory and Keystore alias. Sharing them was a
     * real failure: `deleteDatabase` in teardown does not always win the race
     * against a SQLCipher connection that is still releasing, so the next
     * method found a file encrypted under the previous method's DEK and
     * reported `NeedsRecovery(DatabaseUnopenable)` -- which reads exactly like
     * a bug in the unlock flow and is not.
     */
    private val suffix = System.nanoTime().toString()
    private val keystoreAlias = "lf_bug6_test_$suffix"
    private val testDatabase = "lf-test-bug6-$suffix.db"
    private val keyDirectory = File(context.filesDir, "keys-bug6-$suffix")

    private var session: VaultSession? = null
    private val collectors = mutableListOf<Job>()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() = runBlocking<Unit> {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(testDatabase)
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        // Every ViewModel goes in a store and every store is cleared, so no
        // viewModelScope outlives the test that made it. A leaked scope keeps a
        // debounce collector and a repository alive over a closed session, and
        // the failure it produces lands in whichever test runs next.
        stores.forEach { it.clear() }
        // Every open vault holds a native SQLCipher connection pool; leaving one
        // behind is how a suite dies with a bare "Process crashed".
        runCatching { runBlocking { session?.close() } }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(testDatabase)
    }

    @Test
    fun draftSurvivesProcessDeathAndIsRestoredFieldForField() = runBlocking<Unit> {
        // ── Before ──────────────────────────────────────────────────────────
        val first = openGraph(create = true)
        val groceries = first.newCategory("Groceries")
        val vegetables = first.newCategory("Vegetables", parentId = groceries.id)

        val storeBefore = store()
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

        before.onEvent(EntryEvent.AmountChanged("125"))
        before.select(EntryPicker.Category, groceries.id)
        before.select(EntryPicker.Subcategory(groceries.id), vegetables.id)
        before.onEvent(EntryEvent.NoteChanged("weekly shop, half typed"))
        before.onEvent(EntryEvent.LineItemAdded)

        // Polled, not slept. The debounce is real time and so is the SQLCipher
        // write, and a fixed sleep turns "the device was busy" into a failure —
        // which is exactly the flake this suite cannot afford. Waiting on the
        // row itself also splits the two halves of the test: if this times out
        // the *write* is broken, and if the assertions below fail the *restore*
        // is, rather than both looking identical.
        first.awaitDraftContaining("\"amountMinor\":12500")

        val lineItemKey = before.state.value.lineItems.singleOrNull()?.key
        before.onEvent(EntryEvent.LineItemNameChanged(requireNotNull(lineItemKey), "Rice"))
        before.onEvent(EntryEvent.LineItemAmountChanged(lineItemKey, "60"))
        first.awaitDraftContaining("\"name\":\"Rice\"")

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
        store().put(VIEW_MODEL_KEY, after)
        collectors += CoroutineScope(Dispatchers.Main).launch { after.state.collect {} }

        // Polled for the same reason the write is. `state` is a combine over
        // the form and four database flows, and it emits nothing until all of
        // them have — so a fixed sleep here reads the seed state on a busy
        // device and reports "the draft was lost" when it was merely not shown
        // yet. That is precisely how this test failed in a full-suite run
        // having passed on its own.
        // ADR-0013: the form opens empty and the stack offers what was unsaved,
        // so BUG6's guarantee is now "your typing is still there and reachable"
        // rather than "the form auto-fills". Opening it is the user's tap.
        val stacked = after.awaitState("the unsaved entry in the stack") {
            it.unsaved.isNotEmpty()
        }
        after.onEvent(EntryEvent.DraftOpened(stacked.unsaved.first().id))

        val restored = after.awaitState("the restored amount") { it.amountMinor == 125_00L }

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
        val store = store()
        store.put(VIEW_MODEL_KEY, first.entryViewModel())
        delay(DEBOUNCE_SETTLE_MS)

        assertThat(first.currentDraft()).isNull()

        store.clear()
        first.close()
        session = null

        val second = openGraph(create = false)
        val after = second.entryViewModel()
        store().put(VIEW_MODEL_KEY, after)
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
        val vault = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, testDatabase)

        val outcome = if (create) {
            vault.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), "INR"))
        } else {
            vault.openOnLaunch()
            null
        }
        // A vault that failed to open produces "Vault is locked" several frames
        // later, in whatever the test does next -- which names the symptom and
        // not the cause. Fail here instead.
        assertThat(vault.state.value)
            .isEqualTo(com.ledgerflow.core.domain.vault.VaultState.Unlocked)
        assertThat(outcome?.toString() ?: "Unlocked").isNotEmpty()
        session = vault
        return Graph(vault)
    }

    private fun store(): ViewModelStore = ViewModelStore().also { stores += it }

    /** Waits for [predicate] to hold, or fails saying what never arrived. */
    private suspend fun EntryViewModel.awaitState(
        what: String,
        predicate: (EntryUiState) -> Boolean,
    ): EntryUiState {
        val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return state.value
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError("$what never appeared in the form; last state was ${state.value}")
    }

    private inner class Graph(val vault: VaultSession) {
        private val ids = Uuid7Generator(SecureRandom())
        private val clock = Clock.System

        val storage = DefaultStorageMaintenance(vault, Dispatchers.IO)
        val categories = DefaultCategoryRepository(vault, ids, clock, storage, Dispatchers.IO)
        private val drafts = DefaultDraftRepository(vault, ids, clock, Dispatchers.IO)

        suspend fun currentDraft(): EntryDraft? =
            drafts.observe(LedgerType.DEBIT).first().firstOrNull()

        /**
         * Waits for the debounced write to reach `draft_entry`.
         *
         * The timeout is generous because it is a backstop, not a measurement:
         * on an idle device this returns in well under the debounce window.
         */
        suspend fun awaitDraftContaining(fragment: String) {
            val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (currentDraft()?.payloadJson?.contains(fragment) == true) return
                delay(POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "draft_entry never received a payload containing $fragment -- " +
                    "the debounced write did not land, so the restore below " +
                    "would have failed for the wrong reason.",
            )
        }

        fun entryViewModel() = EntryViewModel(
            approveTransaction = ApproveTransactionUseCase(
                DefaultLedgerRepository(vault, ids, clock, Dispatchers.IO),
            ),
            drafts = DefaultDraftRepository(vault, ids, clock, Dispatchers.IO),
            ledgerRepository = DefaultLedgerRepository(vault, ids, clock, Dispatchers.IO),
            categories = categories,
            merchants = DefaultMerchantRepository(vault, ids, clock, storage, Dispatchers.IO),
            paymentMethods = DefaultPaymentMethodRepository(vault, ids, clock, storage, Dispatchers.IO),
            clock = clock,
            ids = ids,
            // `EntryViewModel` grew a `SavedStateHandle` (the Ledger's
            // tap-to-resume hands it a draft id) and this call site was not
            // updated, so `:app`'s androidTest source set has not compiled
            // since. Nothing reported it because a compile failure in an
            // instrumented source set only surfaces when the instrumented job
            // runs, and CI runs that on a self-hosted runner with a phone
            // attached.
            //
            // Empty rather than seeded: this test opens the form with no
            // argument, which is the "start a new entry" path it is about.
            savedStateHandle = SavedStateHandle(),
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

        /** Only used where the assertion is an *absence*, which no poll can hurry. */
        private const val DEBOUNCE_SETTLE_MS = 800L
        private const val RESTORE_SETTLE_MS = 600L

        /** Backstop for the debounced write, not an expected duration. */
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 50L
    }
}

