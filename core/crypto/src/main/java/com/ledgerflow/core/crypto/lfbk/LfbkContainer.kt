package com.ledgerflow.core.crypto.lfbk

import com.ledgerflow.core.crypto.AesGcm
import com.ledgerflow.core.crypto.KeyDerivation
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Why a `.lfbk` file could not be opened. */
public sealed interface LfbkFailure {
    public data object NotAnLfbkFile : LfbkFailure
    public data class UnsupportedFormat(val version: Int) : LfbkFailure
    public data class Malformed(val reason: String) : LfbkFailure

    /**
     * The `keyCheck` field did not match. The file is intact; these are the
     * wrong words.
     *
     * Distinguishing this from [Corrupt] is the entire reason `keyCheck` exists
     * (SPEC.md §5.9). Without it the Recovery screen -- the one screen that must
     * never feel hopeless -- can only say "something went wrong".
     */
    public data object WrongPhrase : LfbkFailure

    /** The phrase is right but the GCM tag failed: the file is damaged. */
    public data object Corrupt : LfbkFailure
}

public sealed interface LfbkResult {
    public data class Success(val payload: ByteArray, val schemaVersion: Int) : LfbkResult
    public data class Failure(val reason: LfbkFailure) : LfbkResult
}

/**
 * The `.lfbk` backup container (SPEC.md §5.9).
 *
 * ```
 * ┌─ HEADER — authenticated as AAD, never encrypted ────────────────┐
 * │ magic          "LFBK"      4                                    │
 * │ formatVersion  u16         currently 1                          │
 * │ schemaVersion  u32         Room schema version of the payload   │
 * │ kdfId          u8          1 = HKDF-SHA256 / BIP-39             │
 * │ kdfParamsLen   u16         length of kdfParams                  │
 * │ kdfParams      variable    empty for kdfId = 1                  │
 * │ salt           16          HKDF salt, fresh per backup          │
 * │ nonce          12          AES-256-GCM IV, never reused         │
 * │ keyCheck       4                                                │
 * │ plaintextLen   u64                                              │
 * └─────────────────────────────────────────────────────────────────┘
 *   ciphertext     ...         payload + GCM tag
 * ```
 *
 * All integers big-endian. Three properties the original draft lacked:
 *
 * 1. **`kdfParamsLen` is present**, so a reader can find `salt` without already
 *    knowing every KDF's parameter encoding -- which is what a versioned
 *    `kdfId` is supposed to buy.
 * 2. **The header is the AAD.** Otherwise `schemaVersion` is malleable and an
 *    attacker can steer the restore path while the tag still verifies.
 * 3. **`keyCheck` separates "wrong phrase" from "corrupt file."**
 *
 * Encrypted with KEK-B (phrase-derived) **only, never a passphrase** -- a
 * backup can leave the device, so its protection must be the 256-bit phrase
 * (CLAUDE.md §0).
 */
public object LfbkContainer {

    public const val FORMAT_VERSION: Int = 1
    public const val KDF_ID_HKDF_BIP39: Int = 1

    private val MAGIC = byteArrayOf(
        'L'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(),
    )

    /** Refuse to allocate more than this from an untrusted length field. */
    private const val MAX_PLAINTEXT_BYTES = 512L * 1024L * 1024L

    private const val BYTE_MASK = 0xFF
    private const val U16_BYTES = 2
    private const val U32_BYTES = 4
    private const val U64_BYTES = 8

    /** Enough to identify a backup in a log, far too few to be a checksum. */
    private const val FINGERPRINT_BYTES = 8

    /**
     * @param seed the BIP-39 seed (SPEC.md §7.2). Never a passphrase.
     */
    public fun write(
        payload: ByteArray,
        seed: ByteArray,
        schemaVersion: Int,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(KeyDerivation.SALT_LENGTH).also(random::nextBytes)
        val key = KeyDerivation.backupKey(seed, salt)
        val keyCheck = KeyDerivation.keyCheck(seed, salt)

        // The nonce must be known before the header can be built, and the
        // header is the AAD -- so the nonce is generated here rather than
        // inside AesGcm.
        val nonce = ByteArray(AesGcm.NONCE_LENGTH).also(random::nextBytes)
        val header = header(schemaVersion, salt, nonce, keyCheck, payload.size.toLong())
        val sealed = AesGcm.encryptWithNonce(key, payload, nonce, header)

        return header + sealed.ciphertext
    }

    public fun read(bytes: ByteArray, seed: ByteArray): LfbkResult =
        when (val result = parse(bytes)) {
            is ParseResult.Failure -> LfbkResult.Failure(result.reason)
            is ParseResult.Success -> decryptPayload(result.header, seed)
        }

    private fun decryptPayload(parsed: ParsedHeader, seed: ByteArray): LfbkResult {
        // keyCheck first: it separates "wrong words" from "damaged file", and
        // it is the cheap check.
        if (!KeyDerivation.keyCheck(seed, parsed.salt).contentEquals(parsed.keyCheck)) {
            return LfbkResult.Failure(LfbkFailure.WrongPhrase)
        }

        val plaintext = AesGcm.decrypt(
            KeyDerivation.backupKey(seed, parsed.salt),
            AesGcm.Sealed(parsed.nonce, parsed.ciphertext),
            parsed.headerBytes,
        ) ?: return LfbkResult.Failure(LfbkFailure.Corrupt)

        return if (plaintext.size.toLong() != parsed.plaintextLen) {
            LfbkResult.Failure(LfbkFailure.Malformed("declared length disagrees with payload"))
        } else {
            LfbkResult.Success(plaintext, parsed.schemaVersion)
        }
    }

    /** SHA-256 prefix, for logging a backup's identity without its content. */
    public fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it) }

    private fun header(
        schemaVersion: Int,
        salt: ByteArray,
        nonce: ByteArray,
        keyCheck: ByteArray,
        plaintextLen: Long,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(MAGIC)
        writeU16(FORMAT_VERSION)
        writeU32(schemaVersion)
        write(KDF_ID_HKDF_BIP39)
        writeU16(0) // kdfParamsLen: HKDF over a BIP-39 seed takes no parameters.
        write(salt)
        write(nonce)
        write(keyCheck)
        writeU64(plaintextLen)
    }.toByteArray()

    private class ParsedHeader(
        val schemaVersion: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val keyCheck: ByteArray,
        val plaintextLen: Long,
        val headerBytes: ByteArray,
        val ciphertext: ByteArray,
    )

    private sealed interface ParseResult {
        class Success(val header: ParsedHeader) : ParseResult
        class Failure(val reason: LfbkFailure) : ParseResult
    }

    @Suppress("ReturnCount") // Guard clauses; each maps to a distinct LfbkFailure.
    private fun parse(bytes: ByteArray): ParseResult {
        val reader = Reader(bytes)

        if (!reader.take(MAGIC.size).contentEquals(MAGIC)) {
            return ParseResult.Failure(LfbkFailure.NotAnLfbkFile)
        }
        val formatVersion = reader.readU16()
        if (formatVersion != FORMAT_VERSION) {
            return ParseResult.Failure(LfbkFailure.UnsupportedFormat(formatVersion))
        }
        val schemaVersion = reader.readU32()
        if (reader.readByte() != KDF_ID_HKDF_BIP39) {
            return ParseResult.Failure(LfbkFailure.Malformed("unknown kdfId"))
        }

        reader.take(reader.readU16()) // kdfParams, empty for kdfId = 1
        val salt = reader.take(KeyDerivation.SALT_LENGTH)
        val nonce = reader.take(AesGcm.NONCE_LENGTH)
        val keyCheck = reader.take(KeyDerivation.KEY_CHECK_LENGTH)
        val plaintextLen = reader.readU64()
        val headerLength = reader.offset
        val ciphertext = reader.remaining()

        if (reader.overran || ciphertext.isEmpty()) {
            return ParseResult.Failure(LfbkFailure.Malformed("truncated header"))
        }
        // Never trust a length field before the tag verifies (SPEC.md §5.9).
        if (plaintextLen < 0 || plaintextLen > MAX_PLAINTEXT_BYTES) {
            return ParseResult.Failure(LfbkFailure.Malformed("implausible plaintext length"))
        }
        return ParseResult.Success(
            ParsedHeader(
                schemaVersion = schemaVersion,
                salt = salt,
                nonce = nonce,
                keyCheck = keyCheck,
                plaintextLen = plaintextLen,
                headerBytes = bytes.copyOfRange(0, headerLength),
                ciphertext = ciphertext,
            ),
        )
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) = writeBigEndian(value.toLong(), U16_BYTES)

    private fun ByteArrayOutputStream.writeU32(value: Int) = writeBigEndian(value.toLong(), U32_BYTES)

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

        fun readU32(): Int = (0 until U32_BYTES)
            .fold(0) { acc, _ -> (acc shl Byte.SIZE_BITS) or readByte() }

        fun readU64(): Long = (0 until U64_BYTES)
            .fold(0L) { acc, _ -> (acc shl Byte.SIZE_BITS) or readByte().toLong() }

        fun remaining(): ByteArray =
            if (offset >= source.size) ByteArray(0) else source.copyOfRange(offset, source.size)
    }
}
