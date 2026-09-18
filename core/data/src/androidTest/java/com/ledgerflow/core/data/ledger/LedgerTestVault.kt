package com.ledgerflow.core.data.ledger

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.analytics.DefaultRollupRepository
import com.ledgerflow.core.data.inbox.DefaultPendingRepository
import com.ledgerflow.core.data.ingest.AttachmentFiles
import com.ledgerflow.core.data.ingest.DefaultAttachmentRepository
import com.ledgerflow.core.data.taxonomy.DefaultCategoryRepository
import com.ledgerflow.core.data.taxonomy.DefaultMerchantRepository
import com.ledgerflow.core.data.taxonomy.DefaultPaymentMethodRepository
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.DefaultStorageMaintenance
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
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

    /** Reconciliation only — ADR-0006's incremental half is not callable. */
    lateinit var rollups: DefaultRollupRepository
        private set

    /**
     * The real compaction, shared by every repository in this vault.
     *
     * It moved off `LedgerRepository` when the taxonomy purge became a second
     * caller (ADR-0016), so `vault.ledger.compactStorage()` is now
     * `vault.storage.compactStorage()`. Real rather than faked, for the reason
     * `PurgeDeletedEntriesTest` gives: the step worth testing is the one that
     * rewrites the encrypted file.
     */
    lateinit var storage: DefaultStorageMaintenance
        private set

    /** Receipt images (ADR-0023). P4. */
    lateinit var attachments: DefaultAttachmentRepository
        private set

    /** Where those images live, for assertions about the files themselves. */
    lateinit var attachmentFiles: AttachmentFiles
        private set

    /** The Inbox's write side, for step 26's link-on-approval. */
    lateinit var pending: DefaultPendingRepository
        private set

    /** The open handle, for tests asserting on rows this module's ports hide. */
    val database: LedgerFlowDatabase get() = session.requireDatabase()

    /**
     * The phrase this vault was initialised with.
     *
     * Exposed because a `.lfbk` and ADR-0023's sealed images are keyed by the
     * *seed*, not by the DEK, so a test that backs up and restores needs the
     * words — and a restore onto a "new device" is a **second vault with its
     * own DEK** reading a backup written by the first.
     */
    lateinit var mnemonic: List<String>
        private set

    /**
     * @param keepFiles leaves `filesDir/attachments/` alone, for a test that
     *   opens a second vault and expects the first one's sealed images to be
     *   gone or present by its own arrangement rather than by [open]'s.
     */
    suspend fun open(
        phrase: List<String> = Bip39.generate(SecureRandom()),
        keepFiles: Boolean = false,
    ) {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
        if (!keepFiles) File(context.filesDir, "attachments").deleteRecursively()

        val store = FileWrappedDekStore(keyDirectory)
        val dekManager = DekManager(store, AndroidKeystoreKek(keystoreAlias), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, TEST_DATABASE)
        mnemonic = phrase
        session.initialize(VaultInitRequest(phrase, BASE_CURRENCY))

        storage = DefaultStorageMaintenance(session, Dispatchers.IO)
        categories = DefaultCategoryRepository(session, ids, clock, storage, Dispatchers.IO)
        merchants = DefaultMerchantRepository(session, ids, clock, storage, Dispatchers.IO)
        paymentMethods = DefaultPaymentMethodRepository(session, ids, clock, storage, Dispatchers.IO)
        attachmentFiles = AttachmentFiles(context)
        ledger = DefaultLedgerRepository(session, ids, clock, attachmentFiles, Dispatchers.IO)
        drafts = DefaultDraftRepository(session, ids, clock, Dispatchers.IO)
        rollups = DefaultRollupRepository(session, clock, Dispatchers.IO)
        attachments = DefaultAttachmentRepository(attachmentFiles, session, clock, ids, Dispatchers.IO)
        pending = DefaultPendingRepository(session, clock, Dispatchers.IO)
    }

    fun close() {
        runCatching { session.requireDatabase().close() }
        // The images this suite sealed. Left behind they accumulate across
        // runs and the next class's "how many files are there" assertions
        // would count them.
        File(context.filesDir, "attachments").deleteRecursively()
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias)
        }
    }

    /**
     * A `pending_transaction` row, written straight to the DAO.
     *
     * The ingest path is `:core:data`'s own and is exercised elsewhere; what
     * this suite needs is simply a candidate whose `raw_ref_id` it chose, so
     * that approving it can be observed to link — or not link — an image.
     */
    suspend fun seedPendingCandidate(rawRefId: String): String {
        val id = ids.generate()
        session.requireDatabase().pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = id,
                source = EntrySource.OCR,
                dedupeKey = "seed-$id",
                suppressedById = null,
                rawRefId = rawRefId,
                extractedJson = "{}",
                confidence = 0.7,
                status = PendingStatus.PENDING,
                needsManualFill = false,
                createdAt = now,
                reviewedAt = null,
                approvedEntryId = null,
            ),
        )
        return id
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
