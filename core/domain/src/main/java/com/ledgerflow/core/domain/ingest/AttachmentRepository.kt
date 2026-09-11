package com.ledgerflow.core.domain.ingest

/**
 * Storing the image a receipt was read from (SPEC.md §5.3, §7.1; ADR-0023).
 *
 * ## What the caller gets to know, and what it does not
 *
 * An id, and nothing about where the bytes went. The path is relative to
 * `filesDir/attachments/` and the seal is a device-local key — both are
 * storage concerns that a feature has no business holding, and an absolute
 * path escaping into a feature is exactly how ADR-0023's BUG1/BUG2 fuse gets
 * lit.
 *
 * ## Retention: the downscaled image, kept forever
 *
 * ADR-0023 closes §16 Q5. What is stored is the ≤1600px **colour** frame the
 * recogniser actually read — not the camera's original, which is ~15× larger,
 * and not the thresholded one, which is unreadable to a human. Storing what
 * the pipeline saw is what makes "why did OCR read this wrong" answerable
 * later.
 *
 * No timed purge. D-09's 90-day rule exists because a raw message body is the
 * most sensitive text the app holds *and it rides inside a `.lfbk` that can
 * leave the device*; neither clause transfers to an image that is not in the
 * `.lfbk` and is no more sensitive than the entry describing it. Growth is
 * made visible in Settings instead, with a manual bulk delete.
 */
public interface AttachmentRepository {

    /**
     * Seals [bytes] and records the row, or says why it could not.
     *
     * Idempotent on content: the same image stored twice returns the existing
     * attachment rather than a second copy. `sha256` is over the **plaintext**
     * precisely so that this works — a hash of ciphertext under a fresh nonce
     * differs every time and would dedupe nothing.
     *
     * @param bytes the decoded, downscaled image. Plaintext; this call seals it.
     * @param mime what the bytes are, for a later viewer.
     */
    public suspend fun store(bytes: ByteArray, mime: String): AttachmentOutcome

    /**
     * The plaintext image behind [attachmentId], or null.
     *
     * Null covers every honest failure at once — the vault is shut, the row is
     * gone, the file was never written, or the file is there and does not
     * authenticate. The review screen draws a placeholder for all of them,
     * which is ADR-0023's degrade-honestly promise: rows can outlive files, so
     * every surface that draws an attachment has to handle their absence.
     */
    public suspend fun read(attachmentId: String): ByteArray?

    /**
     * How much space receipt images take (ADR-0023).
     *
     * The ADR declines a timed purge — D-09's 90-day rule exists because a raw
     * message body is the most sensitive text the app holds *and* rides inside
     * a `.lfbk` that can leave the device, and neither clause transfers to an
     * image. Growth is made **visible** instead, so the user can decide with
     * the number in front of them.
     *
     * Measured from the files on disk rather than summed from `attachment
     * .bytes`, which records the *plaintext* length: what the user wants to
     * know is what is occupying their phone, and that is the sealed size.
     */
    public suspend fun usage(): AttachmentUsage

    /**
     * Deletes every stored image. Irreversible.
     *
     * **Rows and files, in that order, and the entries survive.** An
     * `attachment` row exists to say an image is there; with the image gone
     * the row would be a promise the app cannot keep, and every surface that
     * draws one would have to distinguish "no receipt" from "receipt we lost".
     * The `ledger_entry` it belonged to is untouched — deleting a photograph
     * is not deleting a purchase.
     *
     * @return how many images were removed.
     */
    public suspend fun deleteAll(): Int
}

/** What the Settings row reports. */
public data class AttachmentUsage(
    val count: Int,
    /** Bytes on disk, sealed — what the phone is actually giving up. */
    val bytes: Long,
)

/** What became of one attempt to store an image. */
public sealed interface AttachmentOutcome {

    /** Sealed and recorded. */
    public data class Stored(val attachmentId: String) : AttachmentOutcome

    /**
     * These exact bytes were already stored; [attachmentId] is the existing row.
     *
     * Distinct from [Stored] so a caller can tell the user "you already
     * scanned this receipt" rather than silently producing a second candidate
     * for a bill they have already filed.
     */
    public data class AlreadyStored(val attachmentId: String) : AttachmentOutcome

    /**
     * The vault is not open, so there is no key to seal with.
     *
     * A real outcome rather than an exception, for §7's BUG13 reason: a throw
     * on a path a background caller can reach lands in a `runCatching` and
     * comes back as a success that did nothing.
     */
    public data object VaultClosed : AttachmentOutcome

    /** The write failed — no space, no permission, a broken filesystem. */
    public data object WriteFailed : AttachmentOutcome
}
