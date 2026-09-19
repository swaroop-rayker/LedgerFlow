package com.ledgerflow.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.BackupOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG27: **a backup folder that no longer exists was failed into, not asked for.**
 *
 * Found on the owner's phone running `TESTING.md` D7: the backup folder was
 * renamed, and "Back up now" still went straight to the 24 words. The check was
 * whether the app *held a grant* — and renaming or deleting a folder leaves the
 * grant in place. The backup would then have failed at the write with a
 * sentence about disk space. The same gap left no way to move backups to
 * another folder (a cloud drive) short of losing the current one.
 *
 * Against a real vault and a real directory; the resolver hands back the same
 * directory whatever happens to it, exactly as a held SAF grant does.
 */
@RunWith(AndroidJUnit4::class)
class Bug27_LostFolderIsAskedForTest {

    private lateinit var vault: LedgerTestVault
    private lateinit var directory: File
    private val released = mutableListOf<String>()

    /** A held grant: resolves whether or not the folder is still there. */
    private val grants = object : BackupFolderResolver {
        override fun resolve(treeUri: String): BackupFolder = FileBackupFolder(directory)
        override fun release(treeUri: String) {
            released += treeUri
        }
    }

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("bug27-test").apply { open() }
        directory = File(vault.context.filesDir, "bug27-folder").apply {
            deleteRecursively()
            mkdirs()
        }
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
        folders = grants,
        clock = vault.clock,
        io = Dispatchers.IO,
    )

    @Test
    fun bug27_aFolderThatWasRenamedOrDeleted_isAskedFor_beforeAnyWords() = runTest {
        repository().setBackupFolder("content://tree/backups")
        assertThat(repository().backupFolderName()).isEqualTo("bug27-folder")

        directory.deleteRecursively()

        assertThat(repository().backupFolderName()).isNull()
        assertThat(repository().backUpNow(vault.mnemonic)).isEqualTo(BackupOutcome.NoBackupFolder)
        assertThat(directory.exists()).isFalse()
        assertThat(vault.database.appMetaDao().value(AppMetaEntity.KEY_LAST_BACKUP_AT)).isNull()
    }

    /**
     * The move to another folder: the new one is recorded and the replaced
     * grant released — but only the replaced one, and a re-choice of the same
     * folder releases nothing.
     */
    @Test
    fun bug27_changingTheFolder_releasesOnlyTheReplacedGrant() = runTest {
        repository().setBackupFolder("content://tree/local")
        repository().setBackupFolder("content://tree/local")
        assertThat(released).isEmpty()

        repository().setBackupFolder("content://drive/tree/cloud")

        assertThat(released).containsExactly("content://tree/local")
    }
}
