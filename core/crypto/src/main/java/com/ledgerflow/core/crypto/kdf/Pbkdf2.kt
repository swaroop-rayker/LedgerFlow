package com.ledgerflow.core.crypto.kdf

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA512, RFC 2898 §5.2.
 *
 * Hand-rolled rather than using `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`,
 * which does exist at minSdk 26 (ADR-0010). The platform API takes a `char[]`
 * via `PBEKeySpec`, and Android implementations have historically disagreed
 * about how characters are encoded into bytes -- the well-known 8-bit
 * truncation behaviour. For an ASCII BIP-39 mnemonic the platform would very
 * likely produce the right answer, but "very likely" is not the standard for
 * the one function whose output, if it differs by a single byte on some OEM
 * ROM, makes that user's backups permanently undecryptable.
 *
 * Taking `ByteArray` directly removes the character-encoding question entirely.
 * SPEC.md §5.8 already establishes that this project does not trust OEM
 * platform data where correctness is load-bearing.
 */
public object Pbkdf2 {

    private const val ALGORITHM = "HmacSHA512"

    /**
     * @param password UTF-8 bytes. Caller owns normalisation (NFKD for BIP-39).
     * @param salt for BIP-39 this is "mnemonic" plus the optional passphrase,
     *   which LedgerFlow leaves empty (SPEC.md §7.2).
     * @param iterations 2048 for BIP-39.
     * @param keyLength output length in bytes; 64 for a BIP-39 seed.
     */
    public fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
    ): ByteArray {
        require(password.isNotEmpty()) { "PBKDF2 password must not be empty" }
        require(iterations > 0) { "PBKDF2 iterations must be positive, was $iterations" }
        require(keyLength > 0) { "PBKDF2 key length must be positive, was $keyLength" }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(password, ALGORITHM))
        val hLen = mac.macLength

        val blockCount = (keyLength + hLen - 1) / hLen
        val output = ByteArray(blockCount * hLen)

        for (blockIndex in 1..blockCount) {
            computeBlock(mac, salt, iterations, blockIndex)
                .copyInto(output, (blockIndex - 1) * hLen)
        }
        return output.copyOf(keyLength)
    }

    /** T_i = U_1 xor U_2 xor ... xor U_c, where U_1 = PRF(P, S | INT(i)). */
    private fun computeBlock(
        mac: Mac,
        salt: ByteArray,
        iterations: Int,
        blockIndex: Int,
    ): ByteArray {
        mac.reset()
        mac.update(salt)
        mac.update(intToBigEndian(blockIndex))

        var u = mac.doFinal()
        val accumulator = u.copyOf()

        repeat(iterations - 1) {
            mac.reset()
            u = mac.doFinal(u)
            for (i in accumulator.indices) {
                accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
            }
        }
        return accumulator
    }

    /** INT(i): the 4-byte big-endian block counter from RFC 2898 §5.2. */
    private fun intToBigEndian(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES) { index ->
        (value ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - index))).toByte()
    }
}
