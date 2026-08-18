package com.ledgerflow.core.data.ledger

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.taxonomy.DefaultCategoryRepository
import com.ledgerflow.core.data.taxonomy.DefaultMerchantRepository
import com.ledgerflow.core.data.taxonomy.DefaultPaymentMethodRepository
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers

/**
 * A real SQLCipher vault for one test class.
 *
 * The ledger layer is tested against the database rather than a fake DAO,
 * because the behaviour under test *is* the database: a transaction that must
 * not half-apply, a unique index on a draft slot, and an invariant no SQLite
 * constraint can express. A fake would assert the code we wrote instead of the
 * schema it has to satisfy.
 *
 * [close] is not tidiness. Every open vault holds a native SQLCipher connection
 * pool, and leaving them open across a suite kills the instrumentation process
 * with a bare "Process crashed" and an empty failure element — a symptom that
 * looks exactly like flake and is not.
 */
internal class LedgerTestVault(private val keystoreAlias: String) {

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Advanced by hand so `created_at`/`updated_at` assertions never race. */
    var now: Long = 1_000L

    val clock: Clock = Clock { now }
    val ids: Uuid7Generator = Uuid7Generator(SecureRandom())

    private val keyDirectory = File(context.filesDir, "keys-$keystoreAlias")

    lateinit var session: VaultSession
        private set

    lateinit var categories: DefaultCategoryRepository
        private set

    lateinit var merchants: DefaultMerchantRepository
        private set

    lateinit var paymentMethods: DefaultPaymentMethodRepository
        private set

    lateinit var ledger: DefaultLedgerRepository
        private set

    lateinit var drafts: DefaultDraftRepository
        private set

    suspend fun open() {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)

        val store = FileWrappedDekStore(keyDirectory)
        val dekManager = DekManager(store, AndroidKeystoreKek(keystoreAlias), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, TEST_DATABASE)
        session.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), BASE_CURRENCY))

        categories = DefaultCategoryRepository(session, ids, clock, Dispatchers.IO)
        merchants = DefaultMerchantRepository(session, ids, clock, Dispatchers.IO)
        paymentMethods = DefaultPaymentMethodRepository(session, ids, clock, Dispatchers.IO)
        ledger = DefaultLedgerRepository(session, ids, clock, Dispatchers.IO)
        drafts = DefaultDraftRepository(session, ids, clock, Dispatchers.IO)
    }

    fun close() {
        runCatching { session.requireDatabase().close() }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias)
        }
    }

    companion object {
        const val BASE_CURRENCY: String = "INR"
    }
}

/**
 * This suite's own database file.
 *
 * Never the production name: these tests run against the app
 * under test and delete their database in teardown, so sharing the real name
 * wiped the debug install's ledger on every run (CLAUDE.md §8, BUG1(e)).
 */
private const val TEST_DATABASE: String = "lf-test-ledger.db"
