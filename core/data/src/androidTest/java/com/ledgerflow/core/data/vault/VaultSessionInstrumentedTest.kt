package com.ledgerflow.core.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.KekId
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultState
import com.ledgerflow.core.crypto.bip39.Bip39
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The §7.3 unlock flow, end to end, against a real SQLCipher database and the
 * device's real Android Keystore.
 *
 * This is the test that Phase 0 could not have: the parts existed and nothing
 * called them. What it asserts is the promise in §7.3 -- that losing the
 * Keystore costs the user nothing but typing, and that no failure path destroys
 * data.
 */
@RunWith(AndroidJUnit4::class)
class VaultSessionInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystoreAlias = "lf_vault_session_test"
    private lateinit var keyDirectory: File

    @Before
    fun setUp() {
        keyDirectory = File(context.filesDir, "keys-test").apply { deleteRecursively() }
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    /**
     * Each test opens several databases -- the point of most of them is that a
     * *second* session behaves like the next process launch. Every one holds a
     * native SQLCipher connection pool, and leaving them open accumulates until
     * the instrumentation process dies with a bare "Process crashed".
     */
    @After
    fun tearDown() {
        opened.forEach { runCatching { it.requireDatabase().close() } }
        opened.clear()
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    private val opened = mutableListOf<VaultSession>()

    private fun session(): VaultSession {
        val store = FileWrappedDekStore(keyDirectory)
        val dekManager = DekManager(store, AndroidKeystoreKek(keystoreAlias), SecureRandom())
        return VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, TEST_DATABASE)
            .also { opened += it }
    }

    private fun mnemonic(): List<String> = Bip39.generate(SecureRandom())

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias)
        }
    }

    @Test
    fun freshInstall_reportsNeedsOnboardingRatherThanFailing() = runBlocking {
        val vault = session()

        vault.openOnLaunch()

        assertThat(vault.state.value).isEqualTo(VaultState.NeedsOnboarding)
    }

    @Test
    fun initialize_opensTheDatabaseAndRecordsTheChosenCurrency() = runBlocking {
        val vault = session()

        val outcome = vault.initialize(
            VaultInitRequest(mnemonic(), baseCurrency = "INR", backupTreeUri = "content://tree/x"),
        )

        assertThat(outcome).isEqualTo(VaultOutcome.Unlocked)
        assertThat(vault.state.value).isEqualTo(VaultState.Unlocked)

        val meta = vault.requireDatabase().appMetaDao()
        assertThat(meta.value("baseCurrency")).isEqualTo("INR")
        assertThat(meta.value(VaultSession.KEY_BACKUP_TREE_URI)).isEqualTo("content://tree/x")
        assertThat(meta.value("canary")).isNotNull()
    }

    @Test
    fun secondLaunch_unlocksSilentlyThroughTheKeystore() = runBlocking {
        session().initialize(VaultInitRequest(mnemonic(), "INR"))

        // A fresh VaultSession is what the next process launch looks like.
        val relaunched = session()
        relaunched.openOnLaunch()

        assertThat(relaunched.state.value).isEqualTo(VaultState.Unlocked)
    }

    /**
     * The case the whole multi-wrap design exists for (SPEC.md §7.2).
     *
     * Deleting the Keystore entry is what a factory reset, a lock-screen change
     * or a device restore does to KEK-A. The user must land on Recovery -- not a
     * crash, not a wipe prompt.
     */
    @Test
    fun keystoreInvalidated_routesToRecoveryAndNeverWipes() = runBlocking {
        session().initialize(VaultInitRequest(mnemonic(), "INR"))
        deleteKeystoreEntry()

        val relaunched = session()
        relaunched.openOnLaunch()

        assertThat(relaunched.state.value).isInstanceOf(VaultState.NeedsRecovery::class.java)
        // The database and the phrase wrap are both still on disk. Nothing about
        // a lost Keystore key may remove either.
        assertThat(context.getDatabasePath(TEST_DATABASE).exists()).isTrue()
        assertThat(File(keyDirectory, KekId.PHRASE.fileName).exists()).isTrue()
    }

    /** §7.3 step 2: the 24 words get the user back in, and re-heal KEK-A. */
    @Test
    fun phraseRecovery_unlocksAndRestoresTheSilentPathForNextTime() = runBlocking {
        val words = mnemonic()
        session().initialize(VaultInitRequest(words, "INR"))
        deleteKeystoreEntry()

        val recovering = session()
        recovering.openOnLaunch()
        val outcome = recovering.unlockWithPhrase(words)

        assertThat(outcome).isEqualTo(VaultOutcome.Unlocked)
        assertThat(recovering.state.value).isEqualTo(VaultState.Unlocked)

        // "User loses nothing" includes not typing 24 words again on the next
        // launch: DekManager re-wrapped KEK-A during recovery.
        val afterHealing = session()
        afterHealing.openOnLaunch()
        assertThat(afterHealing.state.value).isEqualTo(VaultState.Unlocked)
    }

    @Test
    fun wrongPhrase_isRejectedWithoutTouchingTheData() = runBlocking {
        session().initialize(VaultInitRequest(mnemonic(), "INR"))
        deleteKeystoreEntry()

        val recovering = session()
        recovering.openOnLaunch()
        val outcome = recovering.unlockWithPhrase(mnemonic())

        assertThat(outcome).isEqualTo(VaultOutcome.PhraseDidNotMatch)
        assertThat(recovering.state.value).isInstanceOf(VaultState.NeedsRecovery::class.java)
        assertThat(context.getDatabasePath(TEST_DATABASE).exists()).isTrue()
    }

    /**
     * A malformed phrase must come back before any KDF work (CLAUDE.md §7).
     *
     * Timed rather than mocked: 2048 rounds of HMAC-SHA512 is tens of
     * milliseconds even on fast hardware, so a rejection that returns in single
     * digits demonstrably did not run one.
     */
    @Test
    fun malformedPhrase_isRejectedBeforeTheKdfRuns() = runBlocking {
        session().initialize(VaultInitRequest(mnemonic(), "INR"))
        deleteKeystoreEntry()
        val recovering = session()
        recovering.openOnLaunch()

        val startedAt = System.nanoTime()
        val outcome = recovering.unlockWithPhrase(List(24) { "abandon" })
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(outcome).isInstanceOf(VaultOutcome.PhraseRejected::class.java)
        assertThat(elapsedMs).isLessThan(KDF_FLOOR_MS)
    }

    private companion object {
        /**
         * Comfortably below a real PBKDF2 run and comfortably above a checksum
         * check, so the assertion means something without being flaky on a
         * loaded device.
         */
        private const val KDF_FLOOR_MS = 25L
    }
}

/**
 * This suite's own database file.
 *
 * Never the production name: these tests run against the app
 * under test and delete their database in teardown, so sharing the real name
 * wiped the debug install's ledger on every run (CLAUDE.md §8, BUG1(e)).
 */
private const val TEST_DATABASE: String = "lf-test-vault.db"
