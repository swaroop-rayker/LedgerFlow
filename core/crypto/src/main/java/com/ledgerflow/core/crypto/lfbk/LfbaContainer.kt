package com.ledgerflow.core.crypto.lfbk

import com.ledgerflow.core.crypto.AesGcm
import com.ledgerflow.core.crypto.AttachmentBackupKey
import com.ledgerflow.core.crypto.KeyDerivation
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Why a sealed attachment beside the `.lfbk` could not be opened. */
public sealed interface LfbaFailure {
    public data object NotAnLfbaFile : LfbaFailure
    public data class UnsupportedFormat(val version: Int) : LfbaFailure
    public data class Malformed(val reason: String) : LfbaFailure

    /** `keyCheck` did not match: intact file, wrong words. */
    public data object WrongPhrase : LfbaFailure

    /** Right phrase, failed tag: the file is damaged. */
    public data object Corrupt : LfbaFailure

    /**
     * The file is a valid sealed attachment — of a **different** attachment.
     *
     * Distinct from [Corrupt] because it means something different and should
     * read differently in a report: the folder holds this image, under another
     * name. See the container's KDoc for why the id is bound at all.
     */
    public data class WrongAttachment(val expected: String, val found: String) : LfbaFailure
}

public sealed interface LfbaResult {
    public data class Success(val image: ByteArray) : LfbaResult
    public data class Failure(val reason: LfbaFailure) : LfbaResult
}

/**
 * One receipt image, sealed for the backup folder (ADR-0023).
 *
 * ```
 * ┌─ HEADER — authenticated as AAD, never encrypted ────────────────┐
 * │ magic          "LFBA"      4                                    │
 * │ formatVersion  u16         currently 1                          │
 * │ kdfId          u8          1 = HKDF-SHA256 / BIP-39             │
 * │ kdfParamsLen   u16         length of kdfParams; 0 for kdfId = 1 │
 * │ salt           16          HKDF salt, fresh per FILE            │
 * │ nonce          12          AES-256-GCM IV, never reused         │
 * │ keyCheck       4           wrong phrase vs damaged file         │
 * │ idLen          u16         length of attachmentId               │
 * │ attachmentId   variable    UTF-8, the row this image belongs to │
 * │ plaintextLen   u64                                              │
 * └─────────────────────────────────────────────────────────────────┘
 *   ciphertext     ...         image + GCM tag
 * ```
 *
 * **Deliberately a sibling of [LfbkContainer], not a change to it.** ADR-0023
 * chose images *beside* the `.lfbk` rather than inside it, because §5.9's
 * nightly verify-by-decrypt would otherwise rewrite and re-read every image
 * every night. The `.lfbk` container stays `formatVersion` 1 and untouched
 * (`CLAUDE.md` §7), and this file repeats its three hard-won header properties
 * rather than inheriting them: an explicit `kdfParamsLen` so a reader can find
 * the salt without knowing every KDF; the **header as AAD**; and `keyCheck`,
 * so a restore can say "wrong words" instead of one opaque tag failure.
 *
 * ## The attachment id is in the header, and that is not decoration
 *
 * The folder holds one file per image, named by attachment id. Without binding
 * the id into the authenticated header, renaming two files past each other
 * produces two images that decrypt perfectly and belong to the wrong entries.
 *
 * **Measured, not assumed: this is defence in depth rather than the only
 * guard.** Removing the binding and re-running the device round-trip leaves it
 * green, because `AttachmentBackup` checks each restored image against its
 * row's `sha256` and refuses the swap there. What the binding adds is a failure
 * that happens *before* any decryption, and a reason a report can state —
 * "this file belongs to another attachment" rather than "corrupt" — plus a
 * second lock on the one path that could otherwise put a receipt on the wrong
 * entry if that hash check were ever relaxed.
 *
 * ## Per-file salt, per-file nonce
 *
 * A fresh salt per file (so two files share no key material) and a fresh nonce
 * per encryption, generated here because the header is the AAD and must exist
 * before the ciphertext does — the same reason [LfbkContainer] generates its
 * own.
 */
public object LfbaContainer {

    public const val FORMAT_VERSION: Int = 1
    public const val KDF_ID_HKDF_BIP39: Int = 1

    private val MAGIC = byteArrayOf(
        'L'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(),
    )

    /**
     * Refuse to allocate more than this from an untrusted length field.
     *
     * Far below `.lfbk`'s 512 MB: ADR-0023 stores a ≤1600px downscaled frame,
     * about 250 KB, so 64 MB is already three orders of magnitude of headroom
     * and anything larger is not an image this app wrote.
     */
    private const val MAX_IMAGE_BYTES = 64L * 1024L * 1024L

    /** An id is a UUIDv7 string; this is a sanity bound, not a format rule. */
    private const val MAX_ID_BYTES = 256

    private const val BYTE_MASK = 0xFF
    private const val U16_BYTES = 2
    private const val U64_BYTES = 8

    /**
     * @param seed the BIP-39 seed. Never a passphrase (CLAUDE.md §0).
     * @param attachmentId the row this image belongs to, bound into the header.
     */
    public fun write(
        image: ByteArray,
        seed: ByteArray,
        attachmentId: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(KeyDerivation.SALT_LENGTH).also(random::nextBytes)
        val key = AttachmentBackupKey.forBackup(seed, salt)
        val keyCheck = KeyDerivation.keyCheck(seed, salt)
        val nonce = ByteArray(AesGcm.NONCE_LENGTH).also(random::nextBytes)

        val header = header(salt, nonce, keyCheck, attachmentId, image.size.toLong())
        val sealed = AesGcm.encryptWithNonce(key, image, nonce, header)
        return header + sealed.ciphertext
    }

    /** @param attachmentId the row the caller is restoring; must match the file's. */
    public fun read(bytes: ByteArray, seed: ByteArray, attachmentId: String): LfbaResult =
        when (val parsed = parse(bytes)) {
            is ParseResult.Failure -> LfbaResult.Failure(parsed.reason)
            is ParseResult.Success -> decrypt(parsed.header, seed, attachmentId)
        }

    /**
     * Would this file open with [seed], judged from its header alone?
     *
     * The incremental-write check (ADR-0023: an attachment is written to the
     * tree once and never rewritten). "The file exists" is the wrong question
     * after a phrase rotation — every copy in the folder is then sealed under
     * words the user no longer has, and skipping them would leave a backup
     * folder that quietly cannot be restored. This reads `keyCheck` and does
     * no decryption, so re-checking a folder costs a header read per file.
     */
    public fun sealedWith(bytes: ByteArray, seed: ByteArray): Boolean =
        when (val parsed = parse(bytes)) {
            is ParseResult.Failure -> false
            is ParseResult.Success ->
                KeyDerivation.keyCheck(seed, parsed.header.salt)
                    .contentEquals(parsed.header.keyCheck)
        }

    private fun decrypt(
        parsed: ParsedHeader,
        seed: ByteArray,
        attachmentId: String,
    ): LfbaResult {
        // Cheapest first, and it is what separates "wrong words" from "damaged".
        if (!KeyDerivation.keyCheck(seed, parsed.salt).contentEquals(parsed.keyCheck)) {
            return LfbaResult.Failure(LfbaFailure.WrongPhrase)
        }
        // Before decrypting: the id is in the AAD, so a mismatch would fail the
        // tag anyway — but as `Corrupt`, which would send the user looking for
        // a damaged file instead of a misnamed one.
        if (parsed.attachmentId != attachmentId) {
            return LfbaResult.Failure(
                LfbaFailure.WrongAttachment(expected = attachmentId, found = parsed.attachmentId),
            )
        }

        val image = AesGcm.decrypt(
            AttachmentBackupKey.forBackup(seed, parsed.salt),
            AesGcm.Sealed(parsed.nonce, parsed.ciphertext),
            parsed.headerBytes,
        ) ?: return LfbaResult.Failure(LfbaFailure.Corrupt)

        return if (image.size.toLong() != parsed.plaintextLen) {
            LfbaResult.Failure(LfbaFailure.Malformed("declared length disagrees with image"))
        } else {
            LfbaResult.Success(image)
        }
    }

    private fun header(
        salt: ByteArray,
        nonce: ByteArray,
        keyCheck: ByteArray,
        attachmentId: String,
        plaintextLen: Long,
    ): ByteArray = ByteArrayOutputStream().apply {
        val id = attachmentId.toByteArray(Charsets.UTF_8)
        require(id.isNotEmpty() && id.size <= MAX_ID_BYTES) {
            "Attachment id must be 1..$MAX_ID_BYTES bytes, was ${id.size}"
        }
        write(MAGIC)
        writeU16(FORMAT_VERSION)
        write(KDF_ID_HKDF_BIP39)
        writeU16(0) // kdfParamsLen: HKDF over a BIP-39 seed takes no parameters.
        write(salt)
        write(nonce)
        write(keyCheck)
        writeU16(id.size)
        write(id)
        writeU64(plaintextLen)
    }.toByteArray()

    private class ParsedHeader(
        val salt: ByteArray,
        val nonce: ByteArray,
        val keyCheck: ByteArray,
        val attachmentId: String,
        val plaintextLen: Long,
        val headerBytes: ByteArray,
        val ciphertext: ByteArray,
    )

    private sealed interface ParseResult {
        class Success(val header: ParsedHeader) : ParseResult
        class Failure(val reason: LfbaFailure) : ParseResult
    }

    @Suppress("ReturnCount") // Guard clauses; each maps to a distinct LfbaFailure.
    private fun parse(bytes: ByteArray): ParseResult {
        val reader = Reader(bytes)

        if (!reader.take(MAGIC.size).contentEquals(MAGIC)) {
            return ParseResult.Failure(LfbaFailure.NotAnLfbaFile)
        }
        val formatVersion = reader.readU16()
        if (formatVersion != FORMAT_VERSION) {
            return ParseResult.Failure(LfbaFailure.UnsupportedFormat(formatVersion))
        }
        if (reader.readByte() != KDF_ID_HKDF_BIP39) {
            return ParseResult.Failure(LfbaFailure.Malformed("unknown kdfId"))
        }

        reader.take(reader.readU16()) // kdfParams, empty for kdfId = 1
        val salt = reader.take(KeyDerivation.SALT_LENGTH)
        val nonce = reader.take(AesGcm.NONCE_LENGTH)
        val keyCheck = reader.take(KeyDerivation.KEY_CHECK_LENGTH)
        val idLength = reader.readU16()
        if (idLength == 0 || idLength > MAX_ID_BYTES) {
            return ParseResult.Failure(LfbaFailure.Malformed("implausible attachment id length"))
        }
        val id = reader.take(idLength)
        val plaintextLen = reader.readU64()
        val headerLength = reader.offset
        val ciphertext = reader.remaining()

        if (reader.overran || ciphertext.isEmpty()) {
            return ParseResult.Failure(LfbaFailure.Malformed("truncated header"))
        }
        // Never trust a length field before the tag verifies (SPEC.md §5.9).
        if (plaintextLen < 0 || plaintextLen > MAX_IMAGE_BYTES) {
            return ParseResult.Failure(LfbaFailure.Malformed("implausible image length"))
        }
        return ParseResult.Success(
            ParsedHeader(
                salt = salt,
                nonce = nonce,
                keyCheck = keyCheck,
                attachmentId = String(id, Charsets.UTF_8),
                plaintextLen = plaintextLen,
                headerBytes = bytes.copyOfRange(0, headerLength),
                ciphertext = ciphertext,
            ),
        )
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) = writeBigEndian(value.toLong(), U16_BYTES)

    private fun ByteArrayOutputStream.writeU64(value: Long) = writeBigEndian(value, U64_BYTES)

    private fun ByteArrayOutputStream.writeBigEndian(value: Long, byteCount: Int) {
        for (index in byteCount - 1 downTo 0) {
            write(((value ushr (Byte.SIZE_BITS * index)) and BYTE_MASK.toLong()).toInt())
        }
    }

    private class Reader(private val source: ByteArray) {
        var offset: Int = 0
            private set
        var overran: Boolean = false
            private set

        fun take(count: Int): ByteArray {
            if (count < 0 || offset + count > source.size) {
                overran = true
                return ByteArray(0)
            }
            return source.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun readByte(): Int = take(1).firstOrNull()?.toInt()?.and(BYTE_MASK) ?: 0

        fun readU16(): Int = (0 until U16_BYTES)
            .fold(0) { acc, _ -> (acc shl Byte.SIZE_BITS) or readByte() }

        fun readU64(): Long = (0 until U64_BYTES)
            .fold(0L) { acc, _ -> (acc shl Byte.SIZE_BITS) or readByte().toLong() }

        fun remaining(): ByteArray =
            if (offset >= source.size) ByteArray(0) else source.copyOfRange(offset, source.size)
    }
}
