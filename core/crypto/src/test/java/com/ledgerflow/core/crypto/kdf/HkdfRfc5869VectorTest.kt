package com.ledgerflow.core.crypto.kdf

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.hexToBytes
import com.ledgerflow.core.crypto.toHex
import org.junit.Test

/**
 * RFC 5869 Appendix A, the SHA-256 cases. Transcribed from the RFC text itself,
 * not from memory or a third-party summary.
 *
 * This is what makes hand-rolling HKDF defensible (ADR-0010): the construction
 * is checked against the specification's own outputs.
 */
class HkdfRfc5869VectorTest {

    @Test
    fun derive_rfc5869TestCase1_matchesSpecifiedOkm() {
        val okm = Hkdf.derive(
            ikm = "0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
            salt = "0x000102030405060708090a0b0c".hexToBytes(),
            info = "0xf0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            length = 42,
        )

        assertThat(okm.toHex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
    }

    @Test
    fun derive_rfc5869TestCase2_longInputsAndOutput_matchesSpecifiedOkm() {
        val okm = Hkdf.derive(
            ikm = (
                "0x000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                    "404142434445464748494a4b4c4d4e4f"
                ).hexToBytes(),
            salt = (
                "0x606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                    "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                    "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
                ).hexToBytes(),
            info = (
                "0xb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                    "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                    "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
                ).hexToBytes(),
            length = 82,
        )

        assertThat(okm.toHex()).isEqualTo(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87",
        )
    }

    @Test
    fun derive_rfc5869TestCase3_zeroLengthSaltAndInfo_matchesSpecifiedOkm() {
        // Exercises the RFC's "if salt is not provided, set it to HashLen
        // zeros" rule, which SecretKeySpec would otherwise reject outright.
        val okm = Hkdf.derive(
            ikm = "0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42,
        )

        assertThat(okm.toHex()).isEqualTo(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
        )
    }

    @Test
    fun derive_lengthBeyond255Blocks_isRejected() {
        val error = runCatching {
            Hkdf.derive(
                ikm = ByteArray(32) { 1 },
                salt = ByteArray(16),
                info = ByteArray(0),
                length = 255 * 32 + 1,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
