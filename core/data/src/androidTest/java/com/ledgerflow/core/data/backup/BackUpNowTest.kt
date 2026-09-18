package com.ledgerflow.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.BackupOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Back up now" (SPEC.md §16 Q23) against a real vault, with a directory
 * standing in for the SAF tree — `BackupFolderResolver` is the seam, and the
 * directory implementation follows the same verified-write discipline.
 *
 * The properties are about **order**: nothing is sealed under words that are
 * not this vault's, and nothing older is deleted or recorded until the new
 * backup is verified.
 */
@RunWith(AndroidJUnit4::class)
class BackUpNowTest {

    private lateinit var vault: LedgerTestVault
    private lateinit var directory: File

    /** Swappable, so a test can take the folder away or make writes fail. */
    private var folder: BackupFolder? = null

    private val otherPhrase = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 })

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("back-up-now-test").apply { open() }
        directory = File(vault.context.filesDir, "back-up-now-folder").apply {
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

    private fun backups(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".lfbk") }.sorted()

    private suspend fun lastBackupAt(): Long? =
        vault.database.appMetaDao().value(AppMetaEntity.KEY_LAST_BACKUP_AT)?.toLongOrNull()

    /** The whole operation: a verified `.lfbk` that restores under this phrase. */
    @Test
    fun thisVaultsPhrase_writesABackupThatOpensAndRecordsIt() = runTest {
        chooseFolder()

        val outcome = repository().backUpNow(vault.mnemonic)

        assertThat(outcome).isInstanceOf(BackupOutcome.Done::class.java)
        val done = outcome as BackupOutcome.Done
        val written = File(directory, done.fileName)
        assertThat(written.isFile).isTrue()
        assertThat(
            DatabaseBackupManager(vault.database)
                .opensAs(written.readBytes(), Bip39.toSeed(vault.mnemonic), done.rows),
        ).isTrue()
        assertThat(lastBackupAt()).isEqualTo(vault.now)
        assertThat(repository().lastBackupAt().first()).isEqualTo(vault.now)
    }

    /**
     * **The reason the phrase is checked against the vault.** A different,
     * perfectly valid phrase passes every checksum; sealing under it would
     * report success and produce a backup that can never restore.
     */
    @Test
    fun anotherValidPhrase_writesNothingAndRecordsNothing() = runTest {
        chooseFolder()

        val outcome = repository().backUpNow(otherPhrase)

        assertThat(outcome).isEqualTo(BackupOutcome.NotThisVaultsPhrase)
        assertThat(directory.listFiles().orEmpty()).isEmpty()
        assertThat(lastBackupAt()).isNull()
    }

    @Test
    fun noFolderChosen_asksForOne() = runTest {
        assertThat(repository().backUpNow(vault.mnemonic)).isEqualTo(BackupOutcome.NoBackupFolder)
        assertThat(repository().hasBackupFolder()).isFalse()
    }

    /** A grant revoked from system settings reads as "choose a folder", not a crash. */
    @Test
    fun aRevokedFolder_asksForOne() = runTest {
        chooseFolder()
        folder = null

        assertThat(repository().backUpNow(vault.mnemonic)).isEqualTo(BackupOutcome.NoBackupFolder)
    }

    /** Five kept (§8 BUG4(d)), newest first. */
    @Test
    fun rotation_keepsTheFiveNewest() = runTest {
        chooseFolder()
        val names = (1..7).map {
            vault.now += 60_000L
            (repository().backUpNow(vault.mnemonic) as BackupOutcome.Done).fileName
        }

        assertThat(backups()).containsExactlyElementsIn(names.takeLast(5))
    }

    /**
     * Rotation considers only files this app named. Nothing else in the folder
     * is touched.
     *
     * The decoy `.lfbk` is named to sort **oldest**. An earlier version of this
     * test named it `ledgerflow-my-copy.lfbk`, which sorts *after* the
     * timestamped names in descending order, so a rotation that matched every
     * `.lfbk` still kept it among the newest five — the mutation sweep
     * loosened the pattern and this stayed green.
     */
    @Test
    fun rotation_neverTouchesAnythingElseInTheFolder() = runTest {
        chooseFolder()
        File(directory, "notes.txt").writeText("mine")
        File(directory, "an-old-copy-of-mine.lfbk").writeText("also mine")
        repeat(7) {
            vault.now += 60_000L
            repository().backUpNow(vault.mnemonic)
        }

        assertThat(File(directory, "notes.txt").isFile).isTrue()
        assertThat(File(directory, "an-old-copy-of-mine.lfbk").isFile).isTrue()
    }

    /**
     * **A clock set backwards must not delete the backup just made.** The new
     * name then sorts older than the existing five; keeping "the five newest by
     * name" would throw it away.
     */
    @Test
    fun rotation_alwaysKeepsTheBackupJustWritten_evenIfTheClockWentBack() = runTest {
        chooseFolder()
        vault.now = 2_000_000_000_000L
        repeat(5) {
            vault.now += 60_000L
            repository().backUpNow(vault.mnemonic)
        }
        vault.now = 1_000_000_000_000L

        val latest = (repository().backUpNow(vault.mnemonic) as BackupOutcome.Done).fileName

        assertThat(backups()).contains(latest)
        assertThat(backups()).hasSize(5)
    }

    /**
     * **A failed backup changes nothing**: older backups are not rotated out to
     * make room for it, and `lastBackupAt` still names the last good one.
     */
    @Test
    fun aFailedWrite_deletesNothingAndRecordsNothing() = runTest {
        chooseFolder()
        repeat(5) {
            vault.now += 60_000L
            repository().backUpNow(vault.mnemonic)
        }
        val before = backups()
        val lastGood = lastBackupAt()
        folder = object : BackupFolder by FileBackupFolder(directory) {
            override fun writeVerified(name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean) = false
        }
        vault.now += 60_000L

        assertThat(repository().backUpNow(vault.mnemonic)).isEqualTo(BackupOutcome.WriteFailed)
        assertThat(backups()).isEqualTo(before)
        assertThat(lastBackupAt()).isEqualTo(lastGood)
    }

    /** Images ride along, and the second pass finds them already there. */
    @Test
    fun receiptImages_areBackedUpOnceAndCounted() = runTest {
        chooseFolder()
        vault.attachments.store(ByteArray(2_000) { 1 }, "image/jpeg")
        vault.attachments.store(ByteArray(2_000) { 2 }, "image/jpeg")

        val first = repository().backUpNow(vault.mnemonic) as BackupOutcome.Done
        vault.now += 60_000L
        val second = repository().backUpNow(vault.mnemonic) as BackupOutcome.Done

        assertThat(first.imagesWritten).isEqualTo(2)
        assertThat(second.imagesWritten).isEqualTo(0)
        assertThat(second.imagesAlreadyThere).isEqualTo(2)
    }
}
