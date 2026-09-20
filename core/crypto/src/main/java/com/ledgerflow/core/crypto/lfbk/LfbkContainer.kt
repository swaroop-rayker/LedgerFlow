package com.ledgerflow.core.crypto.lfbk

import com.ledgerflow.core.crypto.AesGcm
import com.ledgerflow.core.crypto.KeyDerivation
import com.ledgerflow.core.crypto.kem.BackupSealKem
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

    /** Sealed to a phrase-derived public key (ADR-0027); written without a seed. */
    public const val FORMAT_VERSION_SEALED: Int = 2

    public const val KDF_ID_HKDF_BIP39: Int = 1

    /** `kdfParams` is the KEM's `enc`: the ephemeral public key this file was sealed with. */
    public const val KDF_ID_DHKEM_P256: Int = 2

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
        val header = header(Keying.Phrase, schemaVersion, salt, nonce, keyCheck, payload.size.toLong())
        val sealed = AesGcm.encryptWithNonce(key, payload, nonce, header)

        return header + sealed.ciphertext
    }

    /**
     * A backup sealed to [publicKeyBytes] — the nightly path (ADR-0027).
     *
     * No seed reaches this function, and the ephemeral private key that sealed
     * it is gone when [BackupSealKem.seal] returns, so the writer cannot read
     * back what it wrote. Only the phrase behind [publicKeyBytes] opens it.
     */
    public fun writeSealed(
        payload: ByteArray,
        publicKeyBytes: ByteArray,
        schemaVersion: Int,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val encapsulation = BackupSealKem.seal(publicKeyBytes, random)
        val salt = ByteArray(KeyDerivation.SALT_LENGTH).also(random::nextBytes)
        val key = KeyDerivation.sealedBackupKey(encapsulation.sharedSecret, salt)
        val keyCheck = KeyDerivation.sealedKeyCheck(publicKeyBytes, salt)

        val nonce = ByteArray(AesGcm.NONCE_LENGTH).also(random::nextBytes)
        val header = header(
            keying = Keying.sealedWith(encapsulation.enc),
            schemaVersion = schemaVersion,
            salt = salt,
            nonce = nonce,
            keyCheck = keyCheck,
            plaintextLen = payload.size.toLong(),
        )
        return header + AesGcm.encryptWithNonce(key, payload, nonce, header).ciphertext
    }

    public fun read(bytes: ByteArray, seed: ByteArray): LfbkResult =
        when (val result = parse(bytes)) {
            is ParseResult.Failure -> LfbkResult.Failure(result.reason)
            is ParseResult.Success -> when (result.header.kdfId) {
                KDF_ID_DHKEM_P256 -> decryptSealedPayload(result.header, seed)
                else -> decryptPayload(result.header, seed)
            }
        }

    /**
     * Opens a v2 file, which costs one key derivation from the phrase.
     *
     * The order matters: `keyCheck` is compared before the KEM runs, so the
     * wrong words are answered by a hash comparison rather than by a failed
     * tag — the distinction `keyCheck` exists for.
     */
    private fun decryptSealedPayload(parsed: ParsedHeader, seed: ByteArray): LfbkResult {
        val publicKey = runCatching { BackupSealKem.publicKey(seed) }.getOrNull()
            ?: return LfbkResult.Failure(LfbkFailure.Malformed("seed rejected by the KEM"))
        if (!KeyDerivation.sealedKeyCheck(publicKey, parsed.salt).contentEquals(parsed.keyCheck)) {
            return LfbkResult.Failure(LfbkFailure.WrongPhrase)
        }
        val shared = runCatching { BackupSealKem.open(seed, parsed.kdfParams) }.getOrNull()
            ?: return LfbkResult.Failure(LfbkFailure.Malformed("the sealing key in this file is not a valid point"))

        val plaintext = AesGcm.decrypt(
            KeyDerivation.sealedBackupKey(shared, parsed.salt),
            AesGcm.Sealed(parsed.nonce, parsed.ciphertext),
            parsed.headerBytes,
        ) ?: return LfbkResult.Failure(LfbkFailure.Corrupt)

        return lengthChecked(plaintext, parsed)
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

        return lengthChecked(plaintext, parsed)
    }

    private fun lengthChecked(plaintext: ByteArray, parsed: ParsedHeader): LfbkResult =
        if (plaintext.size.toLong() != parsed.plaintextLen) {
            LfbkResult.Failure(LfbkFailure.Malformed("declared length disagrees with payload"))
        } else {
            LfbkResult.Success(plaintext, parsed.schemaVersion)
        }

    /**
     * Is this a backup sealed to [publicKeyBytes]?
     *
     * The strongest check a writer without the phrase can make on the bytes it
     * read back (ADR-0027, decision b): the file parses as a `.lfbk`, it is the
     * sealed format, and its `keyCheck` is this install's. It cannot say the
     * file decrypts — that needs the words, and `opensAs` is where a manual
     * backup still proves it.
     */
    public fun sealedTo(bytes: ByteArray, publicKeyBytes: ByteArray): Boolean =
        when (val parsed = parse(bytes)) {
            is ParseResult.Failure -> false
            is ParseResult.Success -> parsed.header.kdfId == KDF_ID_DHKEM_P256 &&
                KeyDerivation.sealedKeyCheck(publicKeyBytes, parsed.header.salt)
                    .contentEquals(parsed.header.keyCheck)
        }

    /** SHA-256 prefix, for logging a backup's identity without its content. */
    public fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it) }

    /**
     * How a file is keyed, as one value: the two fields must move together, and
     * `parse` refuses a header that pairs them any other way.
     *
     * `params` is empty for a phrase-keyed file — HKDF over a BIP-39 seed takes
     * none — and the KEM's `enc` for a sealed one.
     */
    private class Keying(val formatVersion: Int, val kdfId: Int, val params: ByteArray) {
        companion object {
            val Phrase = Keying(FORMAT_VERSION, KDF_ID_HKDF_BIP39, ByteArray(0))
            fun sealedWith(enc: ByteArray) = Keying(FORMAT_VERSION_SEALED, KDF_ID_DHKEM_P256, enc)
        }
    }

    private fun header(
        keying: Keying,
        schemaVersion: Int,
        salt: ByteArray,
        nonce: ByteArray,
        keyCheck: ByteArray,
        plaintextLen: Long,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(MAGIC)
        writeU16(keying.formatVersion)
        writeU32(schemaVersion)
        write(keying.kdfId)
        writeU16(keying.params.size)
        write(keying.params)
        write(salt)
        write(nonce)
        write(keyCheck)
        writeU64(plaintextLen)
    }.toByteArray()

    private class ParsedHeader(
        val schemaVersion: Int,
        val kdfId: Int,
        val kdfParams: ByteArray,
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
        if (formatVersion != FORMAT_VERSION && formatVersion != FORMAT_VERSION_SEALED) {
            return ParseResult.Failure(LfbkFailure.UnsupportedFormat(formatVersion))
        }
        val schemaVersion = reader.readU32()
        val kdfId = reader.readByte()
        // The pair is fixed: a version-1 file is phrase-keyed, a version-2 file
        // is sealed. Accepting a mixed pair would mean a header that says one
        // thing and decrypts by another.
        val expectedKdfId = if (formatVersion == FORMAT_VERSION) KDF_ID_HKDF_BIP39 else KDF_ID_DHKEM_P256
        if (kdfId != expectedKdfId) {
            return ParseResult.Failure(LfbkFailure.Malformed("kdfId $kdfId does not belong to format $formatVersion"))
        }

        val kdfParams = reader.take(reader.readU16())
        if (kdfId == KDF_ID_DHKEM_P256 && kdfParams.size != BackupSealKem.PUBLIC_KEY_BYTES) {
            return ParseResult.Failure(LfbkFailure.Malformed("sealed backup carries no usable sealing key"))
        }
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
                kdfId = kdfId,
                kdfParams = kdfParams,
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
