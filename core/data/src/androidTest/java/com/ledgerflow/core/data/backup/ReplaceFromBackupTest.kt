package com.ledgerflow.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.PhraseVerification
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.data.drive.DefaultDriveStatusStore
import com.ledgerflow.core.data.drive.DriveRestoreFolder
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ingest.AttachmentFiles
import com.ledgerflow.core.data.ingest.DefaultAttachmentRepository
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.ReplacedVault
import com.ledgerflow.core.data.vault.VaultFiles
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.backup.BackupPayload
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.LedgerCounts
import com.ledgerflow.core.domain.backup.PutBackOutcome
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultState
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore from Settings, over the phone's vault (ADR-0033), on real vaults: a
 * real SQLCipher file, a real Keystore key, real wraps and real image files.
 *
 * **The scenario throughout.** A phone has its own ledger, under its own words,
 * with a receipt of its own. A backup made elsewhere — an "old phone" that is
 * seeded, backed up into a folder and destroyed — is restored over it. What is
 * under test is the owner's design: nothing changes until the backup has
 * opened and the user has confirmed; the phone's vault is set aside, never
 * deleted, until the restored one has opened cleanly; a restore that does not
 * finish puts it back; and because the Keystore key is kept, the vault that
 * comes back opens with no words.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceFromBackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var folder: File
    private lateinit var sourceWords: List<String>
    private lateinit var expected: BackupPayload

    private val phoneWords: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 })
    private val images = List(2) { index -> ByteArray(2_000 + index) { ((it + index * 11) % 251).toByte() } }
    private val phoneImage = ByteArray(1_500) { (it % 199).toByte() }

    // ── The phone ──
    private val keyDirectory = File(context.filesDir, "keys-$PHONE_ALIAS")
    private val attachmentDirectory = File(context.filesDir, "attachments")
    private val databaseFile get() = context.getDatabasePath(PHONE_DATABASE)
    private val marker get() = File(context.filesDir, PHONE_DATABASE + VaultSession.RESTORE_MARKER_SUFFIX)
    private val setAside get() = File(context.filesDir, PHONE_DATABASE + ReplacedVault.SUFFIX)
    private val files = VaultFiles({ keyDirectory }, { attachmentDirectory })

    private lateinit var dekManager: DekManager
    private lateinit var session: VaultSession
    private lateinit var restores: DefaultRestoreRepository
    private lateinit var phoneImageId: String
    private lateinit var phoneBefore: BackupPayload

    @Before
    fun setUp() = runTest {
        cleanPhone()
        folder = File(context.filesDir, "replace-test-folder").apply {
            deleteRecursively()
            mkdirs()
        }

        // ── The old phone: seed, back up into the folder, destroy. ──
        val old = LedgerTestVault(SOURCE_ALIAS).apply { open() }
        old.categories.seedSystemDefaults()
        old.paymentMethods.seedSystemDefaults()
        images.forEach { bytes -> old.attachments.store(bytes, "image/jpeg") as AttachmentOutcome.Stored }
        old.seedPendingCandidate(rawRefId = "raw-1")
        old.seedPendingCandidate(rawRefId = "raw-2")
        sourceWords = old.mnemonic
        val seed = Bip39.toSeed(sourceWords)
        val manager = DatabaseBackupManager(old.database)
        File(folder, NEWEST).writeBytes(manager.seal(seed).bytes)
        File(folder, UNDER_PHONE_WORDS).writeBytes(manager.seal(Bip39.toSeed(phoneWords)).bytes)
        AttachmentBackup(old.attachmentFiles, old.session, Dispatchers.IO).writeAll(FileBackupFolder(folder), seed)
        expected = manager.export()
        // A backup that opens, and whose import then fails: one category twice
        // trips the unique index part-way through the transaction. The real
        // failure the import's single transaction exists for.
        val duplicated = expected.copy(categories = expected.categories + expected.categories.first())
        File(folder, IMPORT_FAILS).writeBytes(
            LfbkContainer.write(
                payload = Json { encodeDefaults = true }
                    .encodeToString(BackupPayload.serializer(), duplicated).toByteArray(),
                seed = seed,
                schemaVersion = LedgerFlowDatabase.VERSION,
            ),
        )
        old.close()
        // The old phone's images were written to the shared directory; this
        // phone starts with only its own.
        attachmentDirectory.deleteRecursively()

        // ── This phone: its own vault, words, folder and receipt. ──
        newSession()
        session.initialize(VaultInitRequest(phoneWords, "USD", PHONE_TREE))
        phoneImageId = (phoneAttachments().store(phoneImage, "image/jpeg") as AttachmentOutcome.Stored).attachmentId
        phoneBefore = rows()
    }

    @After
    fun tearDown() = runTest {
        session.close()
        cleanPhone()
        folder.deleteRecursively()
    }

    /** A fresh session on this phone — also what a relaunch is. */
    private fun newSession() {
        dekManager = DekManager(FileWrappedDekStore(keyDirectory), AndroidKeystoreKek(PHONE_ALIAS), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, PHONE_DATABASE, files)
        restores = DefaultRestoreRepository(
            session = session,
            attachments = AttachmentBackup(AttachmentFiles(context), session, Dispatchers.IO),
            folders = BackupFolderResolver { uri -> if (uri == TREE) FileBackupFolder(folder) else null },
            documents = BackupDocumentReader { uri -> File(uri).takeIf { it.isFile }?.readBytes() },
            staged = DriveRestoreFolder({ File(context.filesDir, "drive-restore-test") }, Dispatchers.IO),
            io = Dispatchers.IO,
        )
    }

    private suspend fun relaunch() {
        session.close()
        newSession()
        session.openOnLaunch()
    }

    private fun phoneAttachments() = DefaultAttachmentRepository(
        AttachmentFiles(context), session, Clock { 0L }, Uuid7Generator(SecureRandom()), Dispatchers.IO,
    )

    private fun cleanPhone() {
        context.deleteDatabase(PHONE_DATABASE)
        keyDirectory.deleteRecursively()
        marker.delete()
        setAside.deleteRecursively()
        attachmentDirectory.deleteRecursively()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(PHONE_ALIAS) }
    }

    private suspend fun rows(): BackupPayload =
        DatabaseBackupManager(session.requireDatabase()).export().copy(createdAt = 0)

    private suspend fun assertBackupsRows() {
        assertThat(rows().copy(appMeta = emptyList())).isEqualTo(expected.copy(createdAt = 0, appMeta = emptyList()))
    }

    /** The phone as it was before anything was tried: open, its rows, its words, its receipt, nothing aside. */
    private suspend fun assertPhoneAsItWas() {
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertThat(rows()).isEqualTo(phoneBefore)
        assertThat(dekManager.verifyPhrase(phoneWords)).isEqualTo(PhraseVerification.Opens)
        assertWithMessage("the phone's receipt").that(phoneAttachments().read(phoneImageId)).isEqualTo(phoneImage)
        assertWithMessage("marker").that(marker.exists()).isFalse()
        assertWithMessage("set-aside").that(setAside.exists()).isFalse()
    }

    /** What a process death after the set-aside leaves: marker, vault aside, a new wrap in its place. */
    private suspend fun crashAfterTheSetAside() {
        session.close()
        marker.writeText("replace-pending v1")
        assertThat(ReplacedVault(setAside, databaseFile, files).setAside()).isTrue()
        assertThat(dekManager.initialize(sourceWords, keepKeystoreKey = true))
            .isInstanceOf(UnlockResult.Success::class.java)
    }

    private val fromFolder = RestoreSource.InFolder(TREE, NEWEST)

    // ── The whole feature ─────────────────────────────────────────────────

    @Test
    fun replace_bringsBackTheBackup_underItsWords_andKeepsThePhonesVaultAsideUntilTheNextLaunch() = runTest {
        val preview = restores.preview(fromFolder, sourceWords)
        assertThat(preview).isEqualTo(
            RestorePreview.Ready(
                backup = LedgerCounts(expenses = 0, income = 0, inbox = 2),
                phone = LedgerCounts(expenses = 0, income = 0, inbox = 0),
                sameWords = false,
            ),
        )
        assertWithMessage("a preview writes nothing").that(rows()).isEqualTo(phoneBefore)
        assertThat(marker.exists()).isFalse()
        val generationBefore = session.generation.value

        val outcome = restores.replace(fromFolder, sourceWords)

        assertThat(outcome).isEqualTo(
            RestoreOutcome.Done(
                rows = expected.rowCount,
                imagesRestored = images.size,
                imagesNotFound = 0,
                imagesUnreadable = 0,
                imagesFailed = 0,
            ),
        )
        assertBackupsRows()
        assertThat(dekManager.verifyPhrase(sourceWords)).isEqualTo(PhraseVerification.Opens)
        assertThat(dekManager.verifyPhrase(phoneWords)).isNotEqualTo(PhraseVerification.Opens)
        assertWithMessage("the phone's own receipt went with its vault")
            .that(phoneAttachments().read(phoneImageId)).isNull()
        assertThat(marker.exists()).isFalse()
        assertWithMessage("set aside, not deleted").that(setAside.exists()).isTrue()

        // Not the app's until the report is read; then a different vault.
        assertThat(session.state.value).isEqualTo(VaultState.Working)
        assertThat(session.generation.value).isEqualTo(generationBefore)
        restores.finish()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertThat(session.generation.value).isGreaterThan(generationBefore)

        // The next launch opens it silently, and only then lets the old one go.
        relaunch()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertBackupsRows()
        assertThat(setAside.exists()).isFalse()
    }

    @Test
    fun preview_ofABackupUnderThePhonesOwnWords_saysTheWordsStayTheSame() = runTest {
        val preview = restores.preview(RestoreSource.InFolder(TREE, UNDER_PHONE_WORDS), phoneWords)

        assertThat((preview as RestorePreview.Ready).sameWords).isTrue()
    }

    /**
     * BUG87: a replace kept the backup's Google Drive setting, which for a
     * backup made before Drive was turned on is Off. Drive is about this phone
     * and its Google grant, so the phone's setting now survives a replace.
     */
    @Test
    fun Bug87_aReplace_keepsThisPhonesGoogleDriveSetting() = runTest {
        val meta = session.requireDatabase().appMetaDao()
        meta.put(AppMetaEntity(DefaultDriveStatusStore.KEY_ENABLED, "true"))
        meta.put(AppMetaEntity(DefaultDriveStatusStore.KEY_ACCOUNT, "me@example.com"))

        assertThat(restores.replace(fromFolder, sourceWords)).isInstanceOf(RestoreOutcome.Done::class.java)

        val after = rows().appMeta.associate { it.key to it.value }
        assertThat(after[DefaultDriveStatusStore.KEY_ENABLED]).isEqualTo("true")
        assertThat(after[DefaultDriveStatusStore.KEY_ACCOUNT]).isEqualTo("me@example.com")
        assertThat(after[AppMetaEntity.KEY_LAST_BACKUP_AT]).isNotNull()
    }

    /** A backup from a single file names no folder: the phone keeps its own, and its nightly backup with it. */
    @Test
    fun replace_fromASingleFile_keepsThePhonesBackupFolder() = runTest {
        val lone = File(context.filesDir, "replace-test-lone.lfbk").apply { writeBytes(File(folder, NEWEST).readBytes()) }
        try {
            assertThat(restores.replace(RestoreSource.SingleFile(lone.path), sourceWords))
                .isInstanceOf(RestoreOutcome.Done::class.java)

            assertThat(rows().appMeta.firstOrNull { it.key == VaultSession.KEY_BACKUP_TREE_URI }?.value)
                .isEqualTo(PHONE_TREE)
        } finally {
            lone.delete()
        }
    }

    // ── Nothing changes until the backup has opened ────────────────────────

    @Test
    fun replace_withAnotherValidPhrase_saysSo_andThePhoneIsAsItWas() = runTest {
        assertThat(restores.preview(fromFolder, phoneWords))
            .isEqualTo(RestorePreview.Refused(RestoreOutcome.WrongPhrase))
        assertThat(restores.replace(fromFolder, phoneWords)).isEqualTo(RestoreOutcome.WrongPhrase)

        assertPhoneAsItWas()
    }

    @Test
    fun replace_ofADamagedFile_saysSo_andThePhoneIsAsItWas() = runTest {
        val file = File(folder, NEWEST)
        val bytes = file.readBytes()
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThat(restores.replace(fromFolder, sourceWords)).isEqualTo(RestoreOutcome.Damaged)

        assertPhoneAsItWas()
    }

    // ── A restore that does not finish puts the phone's vault back ────────

    /**
     * The in-process failure: the backup opened, the vault was set aside and
     * a new one begun, and the import rolled back. The phone's vault goes back
     * and opens through the Keystore key that was kept — no words.
     */
    @Test
    fun aReplaceWhoseImportFails_putsThePhonesVaultBack_andItOpensWithNoWords() = runTest {
        assertThat(restores.replace(RestoreSource.InFolder(TREE, IMPORT_FAILS), sourceWords))
            .isEqualTo(RestoreOutcome.RolledBack)

        assertPhoneAsItWas()
        relaunch()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertThat(rows()).isEqualTo(phoneBefore)
    }

    @Test
    fun aReplaceInterruptedAfterTheSetAside_canBePutBack_andThePhonesVaultOpensWithNoWords() = runTest {
        crashAfterTheSetAside()

        newSession()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)
        assertWithMessage("background capture must not open a half-built vault")
            .that(session.openForBackgroundWork()).isNull()
        assertThat(restores.canPutBack()).isTrue()

        assertThat(restores.putBack()).isEqualTo(PutBackOutcome.Done)

        assertPhoneAsItWas()
        relaunch()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
    }

    @Test
    fun aReplaceInterruptedAfterTheSetAside_canBeFinished_withTheBackupsWords() = runTest {
        crashAfterTheSetAside()
        newSession()
        session.openOnLaunch()

        assertThat(restores.restore(fromFolder, sourceWords)).isInstanceOf(RestoreOutcome.Done::class.java)
        restores.finish()

        assertBackupsRows()
        assertThat(restores.canPutBack()).isFalse()
        relaunch()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertThat(setAside.exists()).isFalse()
    }

    /**
     * A crash in the middle of the set-aside: the database moved, the wraps
     * not. Finishing the restore must move the old wrap aside first — taken
     * for this restore's own, it would refuse the backup's words as "not the
     * interrupted restore's phrase" and strand the user.
     */
    @Test
    fun aSetAsideInterruptedHalfway_isFinished_notMistakenForTheOldWrap() = runTest {
        session.close()
        marker.writeText("replace-pending v1")
        moveTheDatabaseAsideOnly()

        newSession()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)

        assertThat(restores.restore(fromFolder, sourceWords)).isInstanceOf(RestoreOutcome.Done::class.java)
        assertBackupsRows()
    }

    @Test
    fun aSetAsideInterruptedHalfway_canBePutBack() = runTest {
        session.close()
        marker.writeText("replace-pending v1")
        moveTheDatabaseAsideOnly()

        newSession()
        session.openOnLaunch()
        assertThat(restores.putBack()).isEqualTo(PutBackOutcome.Done)

        assertPhoneAsItWas()
    }

    /**
     * The earliest crash: the marker written, nothing moved yet. The phone's
     * vault never left, and it must be had back without the backup's words —
     * not stranded behind a restore the user can only finish.
     */
    @Test
    fun aReplaceInterruptedBeforeTheFirstMove_canBePutBack() = runTest {
        session.close()
        marker.writeText("replace-pending v1")

        newSession()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)
        assertThat(restores.canPutBack()).isTrue()

        assertThat(restores.putBack()).isEqualTo(PutBackOutcome.Done)

        assertPhoneAsItWas()
    }

    /** A first-run restore's marker has nothing set aside: there is nothing to put back. */
    @Test
    fun aFirstRunRestore_hasNothingToPutBack() = runTest {
        session.close()
        marker.writeText("restore-pending v1")
        newSession()
        session.openOnLaunch()

        assertThat(restores.canPutBack()).isFalse()
        assertThat(restores.putBack()).isEqualTo(PutBackOutcome.Failed)
        marker.delete()
    }

    private fun moveTheDatabaseAsideOnly() {
        val aside = File(setAside, "database").apply { mkdirs() }
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val live = File(databaseFile.parentFile, databaseFile.name + suffix)
            if (live.exists()) assertThat(live.renameTo(File(aside, live.name))).isTrue()
        }
    }

    private companion object {
        const val SOURCE_ALIAS = "replace-test-old-phone"
        const val PHONE_ALIAS = "replace-test-this-phone"
        const val PHONE_DATABASE = "lf-replace-test.db"
        const val TREE = "content://test/tree/backups"
        const val PHONE_TREE = "content://test/tree/this-phone"
        const val NEWEST = "ledgerflow-20261103-030000.lfbk"
        const val UNDER_PHONE_WORDS = "ledgerflow-20261001-030000.lfbk"
        const val IMPORT_FAILS = "ledgerflow-20260901-030000.lfbk"
    }
}
