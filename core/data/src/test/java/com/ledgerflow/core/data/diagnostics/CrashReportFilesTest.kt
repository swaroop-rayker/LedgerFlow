package com.ledgerflow.core.data.diagnostics

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** BUG7(c)'s folder: what is kept, in what order, and what is not a report. */
class CrashReportFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val directory by lazy { File(temp.root, CrashReportFiles.DIRECTORY) }
    private val files by lazy { CrashReportFiles(directory) }

    @Test
    fun newestFirst_andOnlyTheNewestFiveAreKept_soACrashLoopCannotFillThePhone() {
        (1L..8L).forEach { files.write(atMillis = it * 1_000L, text = "report $it") }

        assertThat(files.reports().map { it.readText() })
            .containsExactly("report 8", "report 7", "report 6", "report 5", "report 4").inOrder()
        assertThat(files.latestAt()).isEqualTo(8_000L)
    }

    @Test
    fun aWriteInterruptedBeforeItsRename_isNotAReport() {
        directory.mkdirs()
        File(directory, "crash-5000.txt.tmp").writeText("half a rep")
        files.write(atMillis = 1_000L, text = "whole")

        assertThat(files.reports().map { it.name }).containsExactly("crash-1000.txt")
    }

    @Test
    fun deleteAll_leavesNothing_andAnEmptyFolderHasNoLatest() {
        files.write(atMillis = 1_000L, text = "x")
        files.deleteAll()

        assertThat(files.reports()).isEmpty()
        assertThat(files.latestAt()).isNull()
    }
}
