package com.ledgerflow.core.crypto

import java.io.ByteArrayOutputStream

/** Which factor wrapped a given blob (SPEC.md §7.2). */
public enum class KekId(public val wireValue: Int, public val fileName: String) {
    /** KEK-A. Android Keystore, hardware-backed where available. */
    KEYSTORE(1, "wrapped_dek_ks.bin"),

    /** KEK-B. 24-word phrase. Mandatory, and the only factor that may travel. */
    PHRASE(2, "wrapped_dek_phrase.bin"),

    /**
     * KEK-C. Optional passphrase, **deferred to P1** (SPEC.md §7.2, ADR-0010).
     * The slot is reserved so the format need not change when it lands. It must
     * never wrap a `.lfbk` backup.
     */
    PASSPHRASE(3, "wrapped_dek_pass.bin"),
    ;

    public companion object {
        public fun fromWire(value: Int): KekId? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * On-disk container for a wrapped DEK.
 *
 * ```
 * ┌─ authenticated prefix (passed as GCM AAD) ──────────────┐
 * │ magic         "LFDK"     4 bytes                        │
 * │ formatVersion u8         currently 1                    │
 * │ kekId         u8         KekId.wireValue                │
 * │ saltLength    u8         0 for Keystore, 16 for phrase  │
 * │ salt          saltLength                                │
 * └─────────────────────────────────────────────────────────┘
 *   nonceLength   u8         12
 *   nonce         nonceLength
 *   ciphertext    remainder  wrapped DEK + GCM tag
 * ```
 *
 * **The prefix is GCM AAD.** Without it the salt is attacker-malleable:
 * rewriting the salt in `wrapped_dek_phrase.bin` would otherwise still produce
 * a verifying tag against whatever key that new salt derives. Same reasoning
 * as SPEC.md §5.9 requires for `.lfbk`.
 *
 * The nonce is deliberately *outside* the AAD. GCM already binds it -- a
 * tampered nonce fails the tag on its own -- and keeping it out lets the
 * Android Keystore path work at all: hardware-backed keys generate their own
 * IV during `Cipher.init`, so a header containing the nonce could not be built
 * before encryption.
 */
public object WrappedDekBlob {

    public const val FORMAT_VERSION: Int = 1

    private val MAGIC = byteArrayOf(
        'L'.code.toByte(), 'F'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte(),
    )
    private const val UNSIGNED_BYTE_MASK = 0xFF

    public class Decoded(
        public val kekId: KekId,
        public val salt: ByteArray,
        public val sealed: AesGcm.Sealed,
        /** Re-derived authenticated prefix; pass this back as AAD to decrypt. */
        public val aad: ByteArray,
    )

    /** The authenticated prefix. Must be supplied as AAD when sealing. */
    public fun aad(kekId: KekId, salt: ByteArray): ByteArray {
        require(salt.size <= UNSIGNED_BYTE_MASK) { "Salt too long: ${salt.size}" }
        return ByteArrayOutputStream().apply {
            write(MAGIC)
            write(FORMAT_VERSION)
            write(kekId.wireValue)
            write(salt.size)
            write(salt)
        }.toByteArray()
    }

    public fun encode(kekId: KekId, salt: ByteArray, sealed: AesGcm.Sealed): ByteArray =
        ByteArrayOutputStream().apply {
            write(aad(kekId, salt))
            write(sealed.nonce.size)
            write(sealed.nonce)
            write(sealed.ciphertext)
        }.toByteArray()

    @Suppress("ReturnCount") // Guard clauses; each maps to a distinct UnlockFailure.
    public fun decode(bytes: ByteArray): DecodeResult {
        val reader = Reader(bytes)

        if (!reader.take(MAGIC.size).contentEquals(MAGIC)) {
            return DecodeResult.Failure(UnlockFailure.MalformedBlob("bad magic"))
        }
        val version = reader.readByte()
        if (version != FORMAT_VERSION) {
            return DecodeResult.Failure(UnlockFailure.UnsupportedFormat(version))
        }
        val kekId = KekId.fromWire(reader.readByte())
            ?: return DecodeResult.Failure(UnlockFailure.MalformedBlob("unknown kekId"))

        val salt = reader.take(reader.readByte())
        val nonce = reader.take(reader.readByte())
        val ciphertext = reader.remaining()

        if (reader.overran || nonce.size != AesGcm.NONCE_LENGTH || ciphertext.isEmpty()) {
            return DecodeResult.Failure(UnlockFailure.MalformedBlob("truncated"))
        }
        return DecodeResult.Success(
            Decoded(
                kekId = kekId,
                salt = salt,
                sealed = AesGcm.Sealed(nonce, ciphertext),
                aad = aad(kekId, salt),
            ),
        )
    }

    public sealed interface DecodeResult {
        public data class Success(val blob: Decoded) : DecodeResult
        public data class Failure(val reason: UnlockFailure) : DecodeResult
    }

    private class Reader(private val source: ByteArray) {
        private var offset = 0
        var overran: Boolean = false
            private set

        fun readByte(): Int = take(1).firstOrNull()?.toInt()?.and(UNSIGNED_BYTE_MASK) ?: 0

        fun take(count: Int): ByteArray {
            if (offset + count > source.size) {
                overran = true
                return ByteArray(0)
            }
            return source.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun remaining(): ByteArray =
            if (offset >= source.size) ByteArray(0) else source.copyOfRange(offset, source.size)
    }
}
