package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.crypto.AesGcm
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * How a receipt image is stored on **this device**: 12 bytes of nonce, then the
 * GCM ciphertext-with-tag, under `AttachmentKey.local` (ADR-0023 as amended).
 *
 * Extracted from `DefaultAttachmentRepository` when [AttachmentBackup] became a
 * second reader and writer of the same files. It is deliberately the *only*
 * description of that layout: two copies of a byte format is how a file written
 * by one path stops opening on the other, and the symptom would be receipts
 * that vanish after a restore rather than an error anyone sees.
 *
 * **No AAD, and that is unchanged from the original.** §5.9 requires the
 * `.lfbk` header to be authenticated because it steers the restore path; here
 * there is no header to steer anything with — the row in the database says what
 * the file is — so there is nothing for AAD to protect that the tag does not
 * already cover. The backup copy is a different matter and does bind its own
 * header, including the attachment id (`LfbaContainer`).
 */
internal object LocalAttachmentSeal {

    /** Nonce + ciphertext-with-tag, ready to be written to disk. */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val sealed = AesGcm.encrypt(key = key, plaintext = plaintext)
        return sealed.nonce + sealed.ciphertext
    }

    /**
     * The plaintext, or null if [raw] is truncated or does not authenticate.
     *
     * Null rather than an exception for the reason `AttachmentRepository.read`
     * gives: an absent or damaged image is an expected outcome that every
     * surface already has to draw a placeholder for.
     */
    fun open(key: ByteArray, raw: ByteArray): ByteArray? {
        if (raw.size <= AesGcm.NONCE_LENGTH) return null
        return AesGcm.decrypt(
            key = key,
            sealed = AesGcm.Sealed(
                nonce = raw.copyOfRange(0, AesGcm.NONCE_LENGTH),
                ciphertext = raw.copyOfRange(AesGcm.NONCE_LENGTH, raw.size),
            ),
        )
    }

    /**
     * `.tmp` → write → fsync → rename, §7's backup-writer discipline.
     *
     * The rename is the commit point. Without it a process death mid-write
     * leaves a truncated file that a row already points at, and the failure
     * surfaces much later as an image that will not authenticate.
     */
    fun writeAtomically(target: File, bytes: ByteArray): Boolean = try {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}$TEMP_SUFFIX")

        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            // The bytes, and the directory entry that names them.
            stream.fd.sync()
        }

        if (temp.renameTo(target)) {
            true
        } else {
            temp.delete()
            false
        }
    } catch (_: IOException) {
        false
    }

    /** Over the **plaintext**, which is what `attachment.sha256` records. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    const val TEMP_SUFFIX: String = ".tmp"
}
