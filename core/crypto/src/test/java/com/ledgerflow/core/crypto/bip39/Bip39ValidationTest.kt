package com.ledgerflow.core.crypto.bip39

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.toHex
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.system.measureNanoTime
import org.junit.Test

class Bip39WordlistIntegrityTest {

    /**
     * The wordlist's bytes feed the key derivation. If this hash ever changes,
     * previously generated phrases may map to different entropy and every
     * backup becomes undecryptable -- so the file is pinned as binary in
     * .gitattributes and asserted here.
     *
     * Value is the canonical SHA-256 of bip-0039/english.txt.
     */
    @Test
    fun wordlist_matchesCanonicalSha256() {
        val stream = requireNotNull(Bip39::class.java.getResourceAsStream("/bip39/english.txt"))
        val bytes = stream.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

        assertThat(digest.toHex())
            .isEqualTo("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda")
    }

    @Test
    fun wordlist_has2048WordsFromAbandonToZoo() {
        assertThat(Bip39.words()).hasSize(2048)
        assertThat(Bip39.words().first()).isEqualTo("abandon")
        assertThat(Bip39.words().last()).isEqualTo("zoo")
    }
}

class Bip39ValidationTest {

    private val validPhrase: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES))

    @Test
    fun validate_generatedPhrase_isValid() {
        repeat(20) {
            assertThat(Bip39.validate(Bip39.generate(SecureRandom()))).isEqualTo(MnemonicCheck.Valid)
        }
    }

    @Test
    fun validate_wrongWordCount_reportsCount() {
        val check = Bip39.validate(validPhrase.take(12))

        assertThat(check).isEqualTo(
            MnemonicCheck.Invalid(MnemonicError.WrongWordCount(actual = 12, expected = 24)),
        )
    }

    @Test
    fun validate_unknownWord_reportsOneBasedPosition() {
        val corrupted = validPhrase.toMutableList().apply { this[4] = "notaword" }

        assertThat(Bip39.validate(corrupted)).isEqualTo(
            MnemonicCheck.Invalid(MnemonicError.UnknownWord("notaword", position = 5)),
        )
    }

    @Test
    fun validate_realWordsButWrongChecksum_reportsChecksumMismatch() {
        // 24 x "abandon": every word is in the wordlist and the length is
        // right, so only the checksum can reject it. Deterministic by
        // construction -- all 264 bits are zero, so the encoded checksum is
        // 0x00, while SHA-256(32 zero bytes) begins 0x66. A repeated word is
        // also a realistic transcription error.
        val repeated = List(Bip39.WORD_COUNT) { "abandon" }

        assertThat(Bip39.validate(repeated))
            .isEqualTo(MnemonicCheck.Invalid(MnemonicError.ChecksumMismatch))
    }

    @Test
    fun validate_correctPhraseForZeroEntropy_endsWithArt() {
        // Guards the test above: proves the valid encoding of zero entropy is
        // NOT 24 x "abandon", so that test is really exercising the checksum.
        assertThat(validPhrase.last()).isEqualTo("art")
        assertThat(validPhrase.dropLast(1).toSet()).containsExactly("abandon")
    }

    /**
     * CLAUDE.md §7: "Validate the BIP-39 checksum **before** running
     * Argon2id/HKDF -- otherwise a typo costs the user a 64 MiB KDF round and
     * looks like a hang."
     *
     * Asserts the ordering property directly: rejecting a bad phrase must be
     * orders of magnitude cheaper than deriving a seed from a good one. The
     * threshold is deliberately loose so this does not become a flaky
     * performance test on a loaded CI runner -- it is checking that no KDF runs
     * at all, not measuring throughput.
     */
    @Test
    fun validate_rejectsBadPhraseWithoutRunningTheKdf() {
        // Deterministically invalid (see the checksum test above) rather than a
        // random substitution, which has a 1-in-256 chance of accidentally
        // producing a valid phrase and an intermittently failing test.
        val bad = List(Bip39.WORD_COUNT) { "abandon" }

        // Warm both paths so JIT state is not what is being measured.
        repeat(3) {
            Bip39.validate(bad)
            Bip39.toSeed(validPhrase)
        }

        val validateNanos = measureNanoTime { repeat(100) { Bip39.validate(bad) } } / 100
        val seedNanos = measureNanoTime { Bip39.toSeed(validPhrase) }

        assertThat(Bip39.validate(bad)).isInstanceOf(MnemonicCheck.Invalid::class.java)
        // A 20x margin asserts "no KDF ran here", not a throughput figure, so a
        // loaded CI runner cannot make this flaky. The real ratio is ~1000x.
        assertThat(validateNanos * 20).isLessThan(seedNanos)
    }

    @Test
    fun parse_normalisesCasingAndWhitespace() {
        val messy = "  Abandon\tABANDON \n abandon  "

        assertThat(Bip39.parse(messy)).containsExactly("abandon", "abandon", "abandon").inOrder()
    }

    @Test
    fun toSeed_rejectsInvalidMnemonic() {
        val error = runCatching { Bip39.toSeed(validPhrase.take(23)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
