package com.ledgerflow.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.kem.BackupSealKem
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkResult
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome.SkipReason
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nightly backups (ADR-0027), against a real vault.
 *
 * The properties under test are the ones the design rests on: a pass with no
 * phrase writes a file, that file opens **only** with the phrase, and nothing
 * the device keeps can open it. A directory stands in for the SAF tree, as in
 * `BackUpNowTest`.
 */
@RunWith(AndroidJUnit4::class)
class NightlyBackupTest {

    private lateinit var vault: LedgerTestVault
    private lateinit var directory: File
    private var folder: BackupFolder? = null

    private val otherPhrase = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 })

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("nightly-backup-test").apply { open() }
        directory = File(vault.context.filesDir, "nightly-backup-folder").apply {
            deleteRecursively()
            mkdirs()
        }
        folder = FileBackupFolder(directory)
    }

    @After
    fun tearDown() {
        vault.close()
        directory.deleteRecursively()
    }

    private fun repository() = DefaultBackupRepository(
        session = vault.session,
        dekManager = vault.dekManager,
        attachments = AttachmentBackup(vault.attachmentFiles, vault.session, Dispatchers.IO),
        folders = { folder },
        clock = vault.clock,
        io = Dispatchers.IO,
    )

    private suspend fun chooseFolder() = repository().setBackupFolder("test://folder")

    /** Enrolment is a side effect of a manual backup: the words are there and already proved. */
    private suspend fun enrol(): BackupOutcome = repository().backUpNow(vault.mnemonic)

    private fun backups(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".lfbk") }.sorted()

    private suspend fun lastBackupAt(): Long? =
        vault.database.appMetaDao().value(AppMetaEntity.KEY_LAST_BACKUP_AT)?.toLongOrNull()

    // ── Enrolment ───────────────────────────────────────────────────────────

    @Test
    fun beforeAnyBackup_nightlyIsOffAndSkips() = runTest {
        chooseFolder()

        assertThat(repository().nightlyBackupsEnabled().first()).isFalse()
        assertThat(repository().backUpNightly()).isEqualTo(NightlyBackupOutcome.Skipped(SkipReason.NotEnrolled))
        assertThat(backups()).isEmpty()
        assertThat(lastBackupAt()).isNull()
    }

    @Test
    fun aManualBackup_enrolsOnce_andSaysSoOnlyTheFirstTime() = runTest {
        chooseFolder()

        val first = enrol() as BackupOutcome.Done
        val second = repository().backUpNow(vault.mnemonic) as BackupOutcome.Done

        assertThat(first.nightlyBackupsJustEnabled).isTrue()
        assertThat(second.nightlyBackupsJustEnabled).isFalse()
        assertThat(repository().nightlyBackupsEnabled().first()).isTrue()
    }

    /** Only the public half is stored — the property the whole scheme rests on. */
    @Test
    fun onlyThePublicKeyIsKept() = runTest {
        chooseFolder()
        enrol()

        val stored = vault.database.appMetaDao().value("backupSealPublicKey")
        val expected = BackupSealKem.publicKey(Bip39.toSeed(vault.mnemonic))
            .joinToString("") { "%02x".format(it) }

        assertThat(stored).isEqualTo(expected)
        // 65 bytes is a public point; a private scalar would be 32.
        assertThat(stored!!.length).isEqualTo(BackupSealKem.PUBLIC_KEY_BYTES * 2)
    }

    /** A phrase that never backed up never enrols, so nothing is stored for it. */
    @Test
    fun anotherPhrase_neitherBacksUpNorEnrols() = runTest {
        chooseFolder()

        assertThat(repository().backUpNow(otherPhrase)).isEqualTo(BackupOutcome.NotThisVaultsPhrase)
        assertThat(repository().nightlyBackupsEnabled().first()).isFalse()
    }

    // ── The pass itself ─────────────────────────────────────────────────────

    /** The whole operation: written with no phrase, and it restores with one. */
    @Test
    fun anEnrolledPass_writesABackupThatOnlyThePhraseOpens() = runTest {
        chooseFolder()
        enrol()

        val outcome = repository().backUpNightly()

        assertThat(outcome).isInstanceOf(NightlyBackupOutcome.Done::class.java)
        val done = outcome as NightlyBackupOutcome.Done
        val written = File(directory, done.fileName).readBytes()

        // It opens with the words...
        val read = LfbkContainer.read(written, Bip39.toSeed(vault.mnemonic))
        assertThat(read).isInstanceOf(LfbkResult.Success::class.java)
        assertThat(
            DatabaseBackupManager(vault.database)
                .opensAs(written, Bip39.toSeed(vault.mnemonic), done.rows),
        ).isTrue()

        // ...and with nothing else.
        assertThat(LfbkContainer.read(written, Bip39.toSeed(otherPhrase)))
            .isInstanceOf(LfbkResult.Failure::class.java)
        assertThat(lastBackupAt()).isEqualTo(vault.now)
    }

    /** The sealed format, not the phrase-keyed one — the header says which. */
    @Test
    fun aNightlyBackup_isSealedToThisInstallsKey() = runTest {
        chooseFolder()
        enrol()

        val done = repository().backUpNightly() as NightlyBackupOutcome.Done
        val written = File(directory, done.fileName).readBytes()
        val publicKey = BackupSealKem.publicKey(Bip39.toSeed(vault.mnemonic))

        assertThat(LfbkContainer.sealedTo(written, publicKey)).isTrue()
        assertThat(LfbkContainer.sealedTo(written, BackupSealKem.publicKey(Bip39.toSeed(otherPhrase)))).isFalse()
    }

    @Test
    fun withNoFolder_itSkips_andRecordsNothing() = runTest {
        chooseFolder()
        enrol()
        val enrolledAt = lastBackupAt()
        folder = null

        assertThat(repository().backUpNightly()).isEqualTo(NightlyBackupOutcome.Skipped(SkipReason.NoFolder))
        assertThat(lastBackupAt()).isEqualTo(enrolledAt)
    }

    /** Rotation is the manual path's, unchanged: the newest five, and nothing else touched. */
    @Test
    fun repeatedPasses_keepTheNewestFive_andLeaveOtherFilesAlone() = runTest {
        chooseFolder()
        enrol()
        val bystander = File(directory, "holiday-photos.txt").apply { writeText("not a backup") }

        repeat(6) { index ->
            // Names are stamped to the second, so each pass needs its own.
            vault.now += SECOND_MILLIS
            val outcome = repository().backUpNightly()
            assertThat(outcome).isInstanceOf(NightlyBackupOutcome.Done::class.java)
            if (index == 5) {
                assertThat((outcome as NightlyBackupOutcome.Done).olderBackupsRemoved).isGreaterThan(0)
            }
        }

        assertThat(backups()).hasSize(5)
        assertThat(bystander.readText()).isEqualTo("not a backup")
    }

    /** A pass on a vault that is not open reports it rather than writing an empty backup. */
    /**
     * A vault that cannot be **opened** — onboarding never finished, or a
     * restore is pending — skips rather than writing a backup of nothing.
     *
     * A session with nothing behind it, not a closed one. Two earlier versions
     * of this test were wrong in opposite ways and are worth recording:
     * closing the *session* produced a perfectly good backup, because
     * `openForBackgroundWork()` reopens from the Keystore and that is exactly
     * what lets this run at 3 a.m. (`CLAUDE.md` §7); closing the *database*
     * underneath a live session threw Room's cancellation instead of skipping,
     * which says something about the harness rather than the app.
     */
    @Test
    fun withNoVaultToOpen_itSkips() = runBlocking<Unit> {
        chooseFolder()
        enrol()

        val empty = VaultSession(
            vault.context,
            DekManager(
                FileWrappedDekStore(File(vault.context.filesDir, "keys-nightly-empty")),
                AndroidKeystoreKek("nightly-backup-empty"),
                SecureRandom(),
            ),
            Bip39PhraseValidator(),
            Dispatchers.IO,
            "nightly-empty.db",
        )
        val repository = DefaultBackupRepository(
            session = empty,
            dekManager = vault.dekManager,
            attachments = AttachmentBackup(vault.attachmentFiles, empty, Dispatchers.IO),
            folders = { folder },
            clock = vault.clock,
            io = Dispatchers.IO,
        )

        assertThat(repository.backUpNightly()).isEqualTo(NightlyBackupOutcome.Skipped(SkipReason.VaultClosed))
        assertThat(backups()).hasSize(1) // only the manual one that enrolled
    }

    private companion object {
        const val SECOND_MILLIS = 1_000L
    }
}
