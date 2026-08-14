package com.ledgerflow.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM, from the platform provider (ADR-0010: Tink was rejected on size
 * for a three-call-site surface).
 *
 * The one real hazard with GCM is nonce reuse: encrypting twice under the same
 * key and nonce is catastrophic, not merely weak. This API makes that hard by
 * construction -- [encrypt] always generates its own nonce from [SecureRandom]
 * and returns it. There is no overload that accepts one.
 */
public object AesGcm {

    public const val NONCE_LENGTH: Int = 12
    public const val TAG_LENGTH_BYTES: Int = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_LENGTH_BITS = TAG_LENGTH_BYTES * Byte.SIZE_BITS

    /** Nonce plus ciphertext-with-tag. */
    public data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {

        // ByteArray needs structural equality spelled out; the generated
        // identity comparison would silently break round-trip assertions.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sealed) return false
            return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }

    /**
     * @param aad additional authenticated data. Not encrypted, but covered by
     *   the tag. Headers are passed here so they cannot be tampered with --
     *   SPEC.md §5.9 requires it for `.lfbk`, and the wrapped-DEK blobs follow
     *   the same discipline.
     */
    public fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        random: SecureRandom = SecureRandom(),
    ): Sealed {
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key.asSecretKey(), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return Sealed(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    /** Encrypts with a [SecretKey] that never leaves the Keystore. */
    public fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore provider generates the IV itself; asking for a specific
        // one is rejected outright on hardware-backed keys.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return Sealed(nonce = cipher.iv.copyOf(), ciphertext = cipher.doFinal(plaintext))
    }

    /**
     * Returns null when the tag does not verify -- wrong key, wrong AAD, or a
     * damaged file. Null rather than an exception because at every call site
     * this is an expected outcome that routes to Recovery, and CLAUDE.md §5
     * bans exceptions as control flow.
     */
    public fun decrypt(
        key: ByteArray,
        sealed: Sealed,
        aad: ByteArray = ByteArray(0),
    ): ByteArray? = decryptWith(key.asSecretKey(), sealed, aad)

    /** Decrypts with a [SecretKey] that never leaves the Keystore. */
    public fun decrypt(
        key: SecretKey,
        sealed: Sealed,
        aad: ByteArray = ByteArray(0),
    ): ByteArray? = decryptWith(key, sealed, aad)

    private fun decryptWith(key: SecretKey, sealed: Sealed, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH_BITS, sealed.nonce),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        cipher.doFinal(sealed.ciphertext)
    } catch (_: GeneralSecurityException) {
        // AEADBadTagException and friends. Deliberately not logged: the failure
        // is routine (wrong phrase) and the inputs are key material.
        null
    }

    private fun ByteArray.asSecretKey(): SecretKey = SecretKeySpec(this, KEY_ALGORITHM)
}
