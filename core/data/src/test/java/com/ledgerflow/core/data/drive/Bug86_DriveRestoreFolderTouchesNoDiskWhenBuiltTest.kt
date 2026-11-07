package com.ledgerflow.core.data.drive

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.drive.LocalBackupFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * BUG86: opening the restore screen crashed the debug build.
 *
 * Found 2026-11-07 on the SM-S721B, the first time Settings > Data > Restore
 * from a backup was opened with restore from Drive built in. Hilt builds the
 * restore screen's dependencies on the main thread, and the staging folder's
 * constructor read `context.filesDir` there: a disk read, which StrictMode's
 * `penaltyDeath` turns into a crash (CLAUDE.md §8). Release builds would only
 * have paid the main-thread read.
 *
 * The folder is now found on first use, which is always on the IO dispatcher.
 */
class Bug86_DriveRestoreFolderTouchesNoDiskWhenBuiltTest {

    @Test
    fun building_doesNotLocateTheFolder() {
        var located = 0
        DriveRestoreFolder({ located++; File("unused") }, Dispatchers.Unconfined)

        assertThat(located).isEqualTo(0)
    }

    @Test
    fun theFirstUse_locatesItOnce_andWritesThere() = runBlocking<Unit> {
        val root = Files.createTempDirectory("drive-restore").toFile()
        var located = 0
        val folder = DriveRestoreFolder({ located++; root }, Dispatchers.Unconfined)

        folder.write(LocalBackupFile.Backup("b.lfbk"), byteArrayOf(1))
        folder.write(LocalBackupFile.Attachment("r.lfba"), byteArrayOf(2))

        assertThat(located).isEqualTo(1)
        assertThat(File(root, "b.lfbk").readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(File(root, "attachments/r.lfba").readBytes()).isEqualTo(byteArrayOf(2))
        root.deleteRecursively()
    }
}
