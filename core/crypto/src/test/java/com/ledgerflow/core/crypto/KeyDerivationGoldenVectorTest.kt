package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import org.junit.Test

/**
 * Locks the phrase -> key derivation (SPEC.md §7.2).
 *
 * **If this test fails, the code is wrong. Never re-record the fixture.**
 * Re-recording silently orphans every `.lfbk` a user has ever written -- their
 * backups would still decrypt only with the old build, which no longer exists.
 * CLAUDE.md §7 states this as a rule; this comment states the consequence.
 *
 * On the provenance of these values: HKDF is independently checked against RFC
 * 5869 in [com.ledgerflow.core.crypto.kdf.HkdfRfc5869VectorTest], and PBKDF2
 * against the official BIP-39 vectors in
 * [com.ledgerflow.core.crypto.bip39.Bip39VectorTest]. Those are external
 * sources. What *cannot* come from an external source is the composition --
 * the `info` strings and the order of operations are LedgerFlow's own. Those
 * values were therefore generated once from the validated primitives and
 * frozen here. That is what a golden vector is: not proof the design is
 * correct, but proof it has not drifted since the day it was reviewed.
 */
class KeyDerivationGoldenVectorTest {

    /** Zero entropy: the canonical BIP-39 24-word phrase, 23 x abandon + art. */
    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES))

    /** Fixed, non-random salt so the vector is reproducible. */
    private val salt: ByteArray = ByteArray(KeyDerivation.SALT_LENGTH) { it.toByte() }

    private val seed: ByteArray by lazy { Bip39.toSeed(mnemonic) }

    @Test
    fun seed_zeroEntropyPhrase_emptyPassphrase_isPinned() {
        assertThat(seed.toHex()).isEqualTo(
            "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
        )
    }

    @Test
    fun kekB_isPinned() {
        assertThat(KeyDerivation.kekB(seed, salt).toHex())
            .isEqualTo("193fc8a645f4f306b8f9e84b4270c78a2d1b0d3c27da605c83d05964aa91f71f")
    }

    @Test
    fun backupKey_isPinned() {
        assertThat(KeyDerivation.backupKey(seed, salt).toHex())
            .isEqualTo("6d0ee5ec356c67ca55791d4547df7e96a27ada91d08edf09c2d23e113d014ca6")
    }

    @Test
    fun keyCheck_isPinned() {
        assertThat(KeyDerivation.keyCheck(seed, salt).toHex())
            .isEqualTo("4784f494")
    }

    @Test
    fun purposes_deriveDifferentKeysFromTheSameSeedAndSalt() {
        // The versioned `info` strings are what separate these. If two of them
        // ever collided, the backup key and the DEK-wrapping key would be the
        // same secret and the threat-model separation in SPEC.md §7.2 would be
        // fiction.
        val kekB = KeyDerivation.kekB(seed, salt).toHex()
        val backup = KeyDerivation.backupKey(seed, salt).toHex()

        assertThat(kekB).isNotEqualTo(backup)
    }

    @Test
    fun derivation_rejectsWrongSaltLength() {
        val error = runCatching { KeyDerivation.kekB(seed, ByteArray(8)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
