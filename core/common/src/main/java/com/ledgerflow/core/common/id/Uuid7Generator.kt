package com.ledgerflow.core.common.id

import java.security.SecureRandom

/**
 * UUIDv7 generator (SPEC.md §6.0).
 *
 * Hand-rolled because there is no UUIDv7 anywhere on `minSdk 26` --
 * `java.util.UUID.randomUUID()` is v4 -- and a dependency for 40 lines of bit
 * packing is not worth an artifact.
 *
 * v7 rather than v4 for index locality: the leading 48 bits are a Unix-millis
 * timestamp, so freshly generated ids sort after existing ones and B-tree
 * inserts append rather than scattering across the index. On a table that only
 * ever grows, that is the difference between appending a page and splitting
 * one.
 *
 * Layout (RFC 9562 §5.7):
 * ```
 * 0                   1                   2                   3
 * |             unix_ts_ms (48 bits)              | ver |  rand_a |
 * | var |                   rand_b (62 bits)                      |
 * ```
 */
public class Uuid7Generator(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    public fun generate(): String {
        val bytes = ByteArray(UUID_BYTES)
        random.nextBytes(bytes)

        val timestamp = clock()
        // 48-bit big-endian millisecond timestamp.
        for (index in 0 until TIMESTAMP_BYTES) {
            val shift = Byte.SIZE_BITS * (TIMESTAMP_BYTES - 1 - index)
            bytes[index] = (timestamp ushr shift).toByte()
        }

        // Version 7 in the high nibble of byte 6.
        bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_7).toByte()
        // RFC 4122 variant (binary 10) in the top two bits of byte 8.
        bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and VARIANT_CLEAR) or VARIANT_RFC).toByte()

        return format(bytes)
    }

    private fun format(bytes: ByteArray): String {
        val hex = StringBuilder(CANONICAL_LENGTH)
        bytes.forEachIndexed { index, byte ->
            if (index in HYPHEN_AFTER) hex.append('-')
            hex.append(HEX[(byte.toInt() shr NIBBLE_BITS) and LOW_NIBBLE])
            hex.append(HEX[byte.toInt() and LOW_NIBBLE])
        }
        return hex.toString()
    }

    public companion object {
        private const val UUID_BYTES = 16
        private const val TIMESTAMP_BYTES = 6
        private const val VERSION_BYTE = 6
        private const val VARIANT_BYTE = 8
        private const val VERSION_7 = 0x70
        private const val VARIANT_RFC = 0x80
        private const val VARIANT_CLEAR = 0x3F
        private const val LOW_NIBBLE = 0x0F
        private const val NIBBLE_BITS = 4
        private const val CANONICAL_LENGTH = 36
        private val HYPHEN_AFTER = setOf(4, 6, 8, 10)
        private const val HEX = "0123456789abcdef"
    }
}
