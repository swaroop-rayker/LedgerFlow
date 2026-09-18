package com.ledgerflow.core.data.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

/**
 * The write discipline every backup file goes through (CLAUDE.md §7): temp,
 * flush, verify the bytes that landed, then put in place — and on any failure,
 * nothing under the final name and no temp left behind.
 *
 * Tested on the directory implementation because it is the one a test can
 * drive; `SafBackupFolder` follows the same sequence against a document
 * provider, and its check is the manual step in `TESTING.md`.
 */
class FileBackupFolderTest {

    private val root: File = Files.createTempDirectory("backup-folder-test").toFile()
    private val folder = FileBackupFolder(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun aVerifiedWrite_isInPlaceWithNoTempLeft() {
        assertThat(folder.writeVerified("a.lfbk", byteArrayOf(1, 2, 3)) { true }).isTrue()

        assertThat(folder.read("a.lfbk")).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(folder.list()).containsExactly("a.lfbk")
    }

    /** The verifier sees what landed on storage, not the array handed in. */
    @Test
    fun theVerifier_receivesTheBytesThatLanded() {
        var seen: ByteArray? = null

        folder.writeVerified("a.lfbk", byteArrayOf(9, 8, 7)) { landed -> seen = landed; true }

        assertThat(seen).isEqualTo(byteArrayOf(9, 8, 7))
    }

    /** A write that fails verification leaves nothing — not the file, not the temp. */
    @Test
    fun aFailedVerification_leavesNothingBehind() {
        assertThat(folder.writeVerified("a.lfbk", byteArrayOf(1)) { false }).isFalse()

        assertThat(folder.list()).isEmpty()
        assertThat(folder.exists("a.lfbk")).isFalse()
    }

    /**
     * A failed rewrite keeps the previous copy. The old file is the user's last
     * good one until the new one has been verified.
     */
    @Test
    fun aFailedRewrite_keepsThePreviousCopy() {
        folder.writeVerified("a.lfbk", byteArrayOf(1)) { true }

        assertThat(folder.writeVerified("a.lfbk", byteArrayOf(2)) { false }).isFalse()

        assertThat(folder.read("a.lfbk")).isEqualTo(byteArrayOf(1))
        assertThat(folder.list()).containsExactly("a.lfbk")
    }

    @Test
    fun aSuccessfulRewrite_replacesTheCopy() {
        folder.writeVerified("a.lfbk", byteArrayOf(1)) { true }

        assertThat(folder.writeVerified("a.lfbk", byteArrayOf(2)) { true }).isTrue()

        assertThat(folder.read("a.lfbk")).isEqualTo(byteArrayOf(2))
    }

    @Test
    fun readPrefix_readsOnlyTheHead() {
        folder.writeVerified("a.lfba", ByteArray(1_000) { it.toByte() }) { true }

        assertThat(folder.readPrefix("a.lfba", 4)).isEqualTo(byteArrayOf(0, 1, 2, 3))
        assertThat(folder.readPrefix("missing", 4)).isNull()
    }

    @Test
    fun aSubfolder_isCreatedAndIndependent() {
        val images = requireNotNull(folder.subfolder("attachments"))
        images.writeVerified("x.lfba", byteArrayOf(5)) { true }

        assertThat(images.list()).containsExactly("x.lfba")
        // A subfolder is not a file of its parent.
        assertThat(folder.list()).isEmpty()
    }

    @Test
    fun deletingSomethingAbsent_isNotAFailure() {
        assertThat(folder.delete("never-written")).isTrue()
    }
}
