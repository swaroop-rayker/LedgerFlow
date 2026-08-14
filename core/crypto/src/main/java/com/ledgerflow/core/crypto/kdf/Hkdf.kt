package com.ledgerflow.core.crypto.kdf

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256, RFC 5869.
 *
 * Hand-rolled deliberately (ADR-0010). There is no HKDF in the Android platform
 * at minSdk 26, and the alternatives (Tink, BouncyCastle) each drag in a large
 * dependency for one construction. This is a *construction* over a vetted
 * primitive -- `javax.crypto.Mac` does the actual cryptography; nothing here
 * implements a hash or a cipher.
 *
 * Locked by the RFC 5869 Appendix A test vectors. If those fail, this code is
 * wrong -- the derivation feeds KEK-B and the backup key, and changing it by a
 * single byte orphans every backup a user has ever made.
 */
public object Hkdf {

    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val MAX_EXPAND_BLOCKS = 255

    /**
     * @param ikm input keying material (the BIP-39 seed, for our purposes).
     * @param salt may be empty; RFC 5869 then substitutes HashLen zero bytes.
     * @param info context/purpose string. Versioned -- see [com.ledgerflow.core.crypto.KeyDerivation].
     * @param length output length in bytes, at most 255 * 32.
     */
    public fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length > 0) { "HKDF output length must be positive, was $length" }
        require(length <= MAX_EXPAND_BLOCKS * HASH_LEN) {
            "HKDF output length must be <= ${MAX_EXPAND_BLOCKS * HASH_LEN}, was $length"
        }
        require(ikm.isNotEmpty()) { "HKDF input keying material must not be empty" }

        return expand(extract(salt, ikm), info, length)
    }

    /** RFC 5869 §2.2: PRK = HMAC-Hash(salt, IKM). */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // An all-zero key is legal for HMAC but SecretKeySpec rejects an empty
        // byte array, so the RFC's "HashLen zeros" default is made explicit.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 §2.3: T(n) = HMAC-Hash(PRK, T(n-1) | info | n). */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(prk, ALGORITHM))

        val output = ByteArray(length)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1

        while (written < length) {
            mac.reset()
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()

            val take = minOf(previousBlock.size, length - written)
            previousBlock.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        return output
    }
}
