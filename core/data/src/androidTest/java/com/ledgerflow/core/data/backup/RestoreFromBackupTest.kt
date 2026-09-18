package com.ledgerflow.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.PhraseVerification
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ingest.AttachmentFiles
import com.ledgerflow.core.data.ingest.DefaultAttachmentRepository
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.backup.BackupPayload
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultState
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore from a backup onto a new install (SPEC.md §7.3 step 3, §16 Q11), on
 * real vaults: a real SQLCipher file, a real Keystore key, real wraps.
 *
 * **The scenario throughout is the one the feature exists for.** A phone is
 * lost; its backup folder survives; a new install has nothing — no wrap, no
 * database, no images. The "old phone" is a [LedgerTestVault] that is seeded,
 * backed up into a directory, and then destroyed. The "new phone" is a second
 * session with its own database name, its own key directory and its own
 * Keystore alias, so nothing of the first survives except the folder.
 *
 * What is under test is the owner's decision for §16 Q11: **the backup's own
 * phrase protects the restored vault**, the restore never runs over an
 * existing vault, and nothing is written until the backup has been proven to
 * open under the words.
 */
@RunWith(AndroidJUnit4::class)
class RestoreFromBackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var folder: File
    private lateinit var sourceWords: List<String>
    private lateinit var expected: BackupPayload
    private lateinit var imageIds: List<String>

    private val otherWords: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 })

    private val images = List(2) { index -> ByteArray(2_000 + index) { ((it + index * 11) % 251).toByte() } }

    // ── The new phone ──
    private val keyDirectory = File(context.filesDir, "keys-$TARGET_ALIAS")
    private val databaseFile get() = context.getDatabasePath(TARGET_DATABASE)
    private val marker get() = File(context.filesDir, TARGET_DATABASE + VaultSession.RESTORE_MARKER_SUFFIX)
    private lateinit var dekManager: DekManager
    private lateinit var session: VaultSession
    private lateinit var restores: DefaultRestoreRepository

    @Before
    fun setUp() = runTest {
        cleanTarget()
        folder = File(context.filesDir, "restore-test-folder").apply {
            deleteRecursively()
            mkdirs()
        }

        // ── The old phone: seed, back up into the folder, destroy. ──
        val old = LedgerTestVault(SOURCE_ALIAS).apply { open() }
        old.categories.seedSystemDefaults()
        old.paymentMethods.seedSystemDefaults()
        imageIds = images.map { bytes ->
            (old.attachments.store(bytes, "image/jpeg") as AttachmentOutcome.Stored).attachmentId
        }
        sourceWords = old.mnemonic
        val seed = Bip39.toSeed(sourceWords)
        val manager = DatabaseBackupManager(old.database)
        File(folder, NEWEST).writeBytes(manager.seal(seed).bytes)
        // A second backup of the same data under different words: a folder
        // can hold backups from more than one install.
        File(folder, UNDER_OTHER_WORDS).writeBytes(manager.seal(Bip39.toSeed(otherWords)).bytes)
        AttachmentBackup(old.attachmentFiles, old.session, Dispatchers.IO)
            .writeAll(FileBackupFolder(folder), seed)
        expected = manager.export()
        old.close()

        newPhone()
    }

    @After
    fun tearDown() = runTest {
        session.close()
        cleanTarget()
        folder.deleteRecursively()
    }

    /** A fresh session on the new phone — also what a relaunch is. */
    private fun newPhone() {
        dekManager = DekManager(FileWrappedDekStore(keyDirectory), AndroidKeystoreKek(TARGET_ALIAS), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, TARGET_DATABASE)
        restores = DefaultRestoreRepository(
            session = session,
            attachments = AttachmentBackup(AttachmentFiles(context), session, Dispatchers.IO),
            folders = BackupFolderResolver { uri -> if (uri == TREE) FileBackupFolder(folder) else null },
            documents = BackupDocumentReader { uri -> File(uri).takeIf { it.isFile }?.readBytes() },
            io = Dispatchers.IO,
        )
    }

    private fun cleanTarget() {
        context.deleteDatabase(TARGET_DATABASE)
        keyDirectory.deleteRecursively()
        File(context.filesDir, TARGET_DATABASE + VaultSession.RESTORE_MARKER_SUFFIX).delete()
        File(context.filesDir, "attachments").deleteRecursively()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TARGET_ALIAS) }
    }

    private val fromFolder = RestoreSource.InFolder(TREE, NEWEST)

    private suspend fun restored(): BackupPayload = DatabaseBackupManager(session.requireDatabase()).export()

    /** Row-level equality, table by table; `app_meta` is compared by [assertMetaCarriedOver]. */
    private fun assertSameRows(actual: BackupPayload) {
        assertThat(actual.copy(createdAt = 0, appMeta = emptyList()))
            .isEqualTo(expected.copy(createdAt = 0, appMeta = emptyList()))
    }

    private fun assertMetaCarriedOver(actual: BackupPayload, backupTreeUri: String?) {
        val folderKey = VaultSession.KEY_BACKUP_TREE_URI
        assertThat(actual.appMeta.filterNot { it.key == folderKey })
            .containsExactlyElementsIn(expected.appMeta.filterNot { it.key == folderKey })
        assertThat(actual.appMeta.firstOrNull { it.key == folderKey }?.value).isEqualTo(backupTreeUri)
    }

    /** Nothing of a vault exists: no wrap, no database, no marker. */
    private fun assertNothingWritten() {
        assertWithMessage("phrase wrap").that(dekManager.isInitialized()).isFalse()
        assertWithMessage("database").that(databaseFile.exists()).isFalse()
        assertWithMessage("marker").that(marker.exists()).isFalse()
    }

    // ── The whole feature ─────────────────────────────────────────────────

    @Test
    fun restore_ontoANewPhone_returnsEveryRowAndImage_underTheBackupsOwnPhrase() = runTest {
        val outcome = restores.restore(fromFolder, sourceWords)

        assertThat(outcome).isEqualTo(
            RestoreOutcome.Done(
                rows = expected.rowCount,
                imagesRestored = images.size,
                imagesNotFound = 0,
                imagesUnreadable = 0,
                imagesFailed = 0,
            ),
        )
        val actual = restored()
        assertSameRows(actual)
        assertMetaCarriedOver(actual, backupTreeUri = TREE)
        val attachments = DefaultAttachmentRepository(
            AttachmentFiles(context), session, Clock { 0L }, Uuid7Generator(SecureRandom()), Dispatchers.IO,
        )
        imageIds.forEachIndexed { index, id ->
            assertWithMessage("image $index").that(attachments.read(id)).isEqualTo(images[index])
        }

        // **The owner's decision**: the backup's words open this vault, and no
        // other words do. No second phrase was issued.
        assertThat(dekManager.verifyPhrase(sourceWords)).isEqualTo(PhraseVerification.Opens)
        assertThat(dekManager.verifyPhrase(otherWords)).isNotEqualTo(PhraseVerification.Opens)
        assertThat(marker.exists()).isFalse()

        // Not handed to the app until the user has read the report...
        assertThat(session.state.value).isEqualTo(VaultState.Working)
        restores.finish()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)

        // ...and a relaunch opens it silently, through the Keystore wrap.
        session.close()
        newPhone()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.Unlocked)
        assertSameRows(restored())
    }

    // ── Nothing is written until the backup is proven to open ─────────────

    @Test
    fun restore_withAnotherValidPhrase_saysSo_andWritesNothing() = runTest {
        assertThat(restores.restore(fromFolder, otherWords)).isEqualTo(RestoreOutcome.WrongPhrase)

        assertNothingWritten()
    }

    @Test
    fun restore_ofADamagedFile_saysSo_andWritesNothing() = runTest {
        val file = File(folder, NEWEST)
        val bytes = file.readBytes()
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThat(restores.restore(fromFolder, sourceWords)).isEqualTo(RestoreOutcome.Damaged)

        assertNothingWritten()
    }

    @Test
    fun restore_ofABackupFromANewerVersion_saysSo_andWritesNothing() = runTest {
        val newer = LfbkContainer.write(
            payload = "{}".toByteArray(),
            seed = Bip39.toSeed(sourceWords),
            schemaVersion = LedgerFlowDatabase.VERSION + 1,
        )
        File(folder, NEWEST).writeBytes(newer)

        assertThat(restores.restore(fromFolder, sourceWords))
            .isEqualTo(RestoreOutcome.NewerVersion(LedgerFlowDatabase.VERSION + 1, LedgerFlowDatabase.VERSION))

        assertNothingWritten()
    }

    @Test
    fun restore_fromAFolderThatIsGone_saysSo_andWritesNothing() = runTest {
        assertThat(restores.restore(RestoreSource.InFolder("content://revoked", NEWEST), sourceWords))
            .isEqualTo(RestoreOutcome.SourceUnreadable)

        assertNothingWritten()
    }

    /** §16 Q11 (c): restore replaces onboarding, never a vault. */
    @Test
    fun restore_ontoAPhoneThatAlreadyHasAVault_isRefused_andTheVaultIsUntouched() = runTest {
        session.initialize(VaultInitRequest(otherWords, "INR"))
        val before = restored().copy(createdAt = 0)

        assertThat(restores.restore(fromFolder, sourceWords)).isEqualTo(RestoreOutcome.AlreadySetUp)

        assertThat(restored().copy(createdAt = 0)).isEqualTo(before)
        assertThat(dekManager.verifyPhrase(otherWords)).isEqualTo(PhraseVerification.Opens)
        assertThat(marker.exists()).isFalse()
    }

    // ── Interrupted restores ──────────────────────────────────────────────

    /**
     * The crash window the marker exists for: the wrap is written and the
     * database is not. The ordinary launch would open an empty, canary-less
     * database and route to Recovery with the wrong sentence; this routes back
     * to the restore, keeps background capture out, and only the words the
     * wrap was made under can finish it.
     */
    @Test
    fun aRestoreInterruptedAfterTheWrap_returnsToRestore_andOnlyItsWordsFinishIt() = runTest {
        marker.writeText("restore-pending v1")
        assertThat(dekManager.initialize(sourceWords)).isInstanceOf(UnlockResult.Success::class.java)

        newPhone()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)
        assertWithMessage("background capture must not open a half-built vault")
            .that(session.openForBackgroundWork()).isNull()

        // A backup that opens under other words is not this restore's.
        assertThat(restores.restore(RestoreSource.InFolder(TREE, UNDER_OTHER_WORDS), otherWords))
            .isEqualTo(RestoreOutcome.NotTheInterruptedRestoresPhrase)
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)
        assertThat(marker.exists()).isTrue()

        assertThat(restores.restore(fromFolder, sourceWords)).isInstanceOf(RestoreOutcome.Done::class.java)
        assertSameRows(restored())
        assertThat(marker.exists()).isFalse()
    }

    /**
     * A crash after the rows committed and before the images finished: the
     * relaunch returns to the restore, which must recognise the rows are in
     * rather than insert them a second time — which would fail every unique
     * constraint and leave the user stuck on this screen for good.
     */
    @Test
    fun aRestoreInterruptedAfterTheRowsCommitted_isNotImportedTwice() = runTest {
        assertThat(restores.restore(fromFolder, sourceWords)).isInstanceOf(RestoreOutcome.Done::class.java)
        marker.writeText("restore-pending v1")
        session.close()

        newPhone()
        session.openOnLaunch()
        assertThat(session.state.value).isEqualTo(VaultState.RestoreInterrupted)

        val again = restores.restore(fromFolder, sourceWords)

        assertThat(again).isEqualTo(
            RestoreOutcome.Done(
                rows = expected.rowCount,
                imagesRestored = images.size,
                imagesNotFound = 0,
                imagesUnreadable = 0,
                imagesFailed = 0,
            ),
        )
        assertSameRows(restored())
        assertThat(marker.exists()).isFalse()
    }

    // ── A backup without its folder ───────────────────────────────────────

    /** ADR-0023's honest degradation: the rows come back, and the images are counted. */
    @Test
    fun aSingleFile_bringsBackTheRows_andCountsEveryImageAsNotFound() = runTest {
        val lone = File(context.filesDir, "restore-test-lone.lfbk").apply { writeBytes(File(folder, NEWEST).readBytes()) }
        try {
            val outcome = restores.restore(RestoreSource.SingleFile(lone.path), sourceWords)

            assertThat(outcome).isEqualTo(
                RestoreOutcome.Done(
                    rows = expected.rowCount,
                    imagesRestored = 0,
                    imagesNotFound = images.size,
                    imagesUnreadable = 0,
                    imagesFailed = 0,
                ),
            )
            val actual = restored()
            assertSameRows(actual)
            // The old phone's folder is not this one's, and no folder was chosen.
            assertMetaCarriedOver(actual, backupTreeUri = null)
        } finally {
            lone.delete()
        }
    }

    private companion object {
        const val SOURCE_ALIAS = "restore-test-old-phone"
        const val TARGET_ALIAS = "restore-test-new-phone"
        const val TARGET_DATABASE = "lf-restore-test.db"
        const val TREE = "content://test/tree/backups"
        const val NEWEST = "ledgerflow-20260918-101500.lfbk"
        const val UNDER_OTHER_WORDS = "ledgerflow-20260901-080000.lfbk"
    }
}
