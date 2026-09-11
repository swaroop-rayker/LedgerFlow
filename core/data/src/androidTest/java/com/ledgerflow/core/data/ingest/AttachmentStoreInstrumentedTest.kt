package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Receipt images on disk (ADR-0023, amended). Schema v11, P4 step 13.
 *
 * Instrumented rather than JVM because every property worth asserting here is
 * about the real thing: a real DEK from a real unlock, a real derived key, a
 * real file under a real `filesDir`. A fake would assert that the code agrees
 * with itself.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStoreInstrumentedTest {

    private lateinit var vault: LedgerTestVault

    private val image = ByteArray(4_096) { (it % 251).toByte() }

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("attachment-store-test").apply { open() }
    }

    @After
    fun tearDown() {
        vault.close()
    }

    private fun attachmentsDir() = File(vault.context.filesDir, "attachments")

    @Test
    fun storedImage_readsBackByteForByte() = runTest {
        val stored = vault.attachments.store(image, "image/png")

        assertThat(stored).isInstanceOf(AttachmentOutcome.Stored::class.java)
        val id = (stored as AttachmentOutcome.Stored).attachmentId
        assertThat(vault.attachments.read(id)).isEqualTo(image)
    }

    /**
     * The bytes on disk must not be the bytes that went in.
     *
     * The one assertion that actually proves the file is sealed. Everything
     * else here would pass just as well against a store that wrote plaintext,
     * which is precisely the bug worth catching — it would be invisible until
     * someone pulled the file off a device.
     */
    @Test
    fun theFileOnDisk_isNotThePlaintext() = runTest {
        vault.attachments.store(image, "image/png")

        val file = attachmentsDir().listFiles().orEmpty().single()
        val onDisk = file.readBytes()

        assertThat(onDisk).isNotEqualTo(image)
        // Nonce + tag, so it is larger by exactly that much.
        assertThat(onDisk.size).isEqualTo(image.size + NONCE + TAG)
        // A crude but decisive check that no long run of the plaintext
        // survived: the first 64 bytes must not appear anywhere in the file.
        assertThat(onDisk.toList().windowed(64).any { it == image.take(64) }).isFalse()
    }

    /**
     * `file_path` is relative — ADR-0023's BUG1/BUG2 fuse.
     *
     * `filesDir` differs across a reinstall and across a restore onto another
     * device. An absolute path resolves on the machine that wrote it and
     * nowhere else, and the symptom is a receipt that vanished rather than an
     * error.
     */
    @Test
    fun theStoredPath_isRelative() = runTest {
        val id = (vault.attachments.store(image, "image/png") as AttachmentOutcome.Stored)
            .attachmentId

        val row = requireNotNull(vault.database.attachmentDao().byId(id))

        assertThat(File(row.filePath).isAbsolute).isFalse()
        assertThat(row.filePath).doesNotContain(vault.context.filesDir.absolutePath)
    }

    /** Law 5: internal storage, never `cacheDir`. */
    @Test
    fun theFileLivesInFilesDir_notCacheDir() = runTest {
        vault.attachments.store(image, "image/png")

        assertThat(attachmentsDir().listFiles().orEmpty()).hasLength(1)
        assertThat(File(vault.context.cacheDir, "attachments").exists()).isFalse()
    }

    /**
     * The same receipt twice is one image and one row.
     *
     * This is what makes a second scan of one bill reach the candidate write
     * with the id the first one used, so it comes back `AlreadyPending`
     * instead of producing a twin.
     */
    @Test
    fun theSameImageTwice_storesOnce() = runTest {
        val first = vault.attachments.store(image, "image/png")
        val second = vault.attachments.store(image, "image/png")

        assertThat(first).isInstanceOf(AttachmentOutcome.Stored::class.java)
        assertThat(second).isInstanceOf(AttachmentOutcome.AlreadyStored::class.java)
        assertThat((second as AttachmentOutcome.AlreadyStored).attachmentId)
            .isEqualTo((first as AttachmentOutcome.Stored).attachmentId)

        assertThat(attachmentsDir().listFiles().orEmpty()).hasLength(1)
        assertThat(vault.database.attachmentDao().all()).hasSize(1)
    }

    @Test
    fun aDifferentImage_storesSeparately() = runTest {
        val other = ByteArray(4_096) { (it % 97).toByte() }

        vault.attachments.store(image, "image/png")
        vault.attachments.store(other, "image/png")

        assertThat(vault.database.attachmentDao().all()).hasSize(2)
        assertThat(attachmentsDir().listFiles().orEmpty()).hasLength(2)
    }

    /**
     * ADR-0023's honest-degradation case, one layer down.
     *
     * A `.lfbk` moved without its sibling images restores rows whose files are
     * absent. Reading one must report nothing rather than crash or hand back
     * an empty image that a viewer would draw as a blank receipt.
     */
    @Test
    fun aMissingFile_readsAsNull_ratherThanCrashing() = runTest {
        val id = (vault.attachments.store(image, "image/png") as AttachmentOutcome.Stored)
            .attachmentId
        attachmentsDir().listFiles().orEmpty().forEach { it.delete() }

        assertThat(vault.attachments.read(id)).isNull()
    }

    /** A damaged file fails authentication rather than returning rubbish. */
    @Test
    fun aTamperedFile_doesNotAuthenticate() = runTest {
        val id = (vault.attachments.store(image, "image/png") as AttachmentOutcome.Stored)
            .attachmentId

        val file = attachmentsDir().listFiles().orEmpty().single()
        val bytes = file.readBytes()
        // One bit, in the ciphertext rather than the nonce.
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThat(vault.attachments.read(id)).isNull()
    }

    /** No `.tmp` survives a successful write. */
    @Test
    fun theWrite_leavesNoTempFileBehind() = runTest {
        vault.attachments.store(image, "image/png")

        assertThat(attachmentsDir().listFiles().orEmpty().map { it.name })
            .containsNoneOf(".tmp", "tmp")
        assertThat(attachmentsDir().listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            .isTrue()
    }

    private companion object {
        const val NONCE = 12
        const val TAG = 16
    }
}
