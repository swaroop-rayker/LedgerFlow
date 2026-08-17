package com.ledgerflow.core.data.vault

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import org.junit.Test

/**
 * The adapter between `:core:crypto`'s BIP-39 and the domain vocabulary.
 *
 * The derivation itself is pinned by the golden vectors in `:core:crypto` and is
 * not retested here. What matters at this seam is that every crypto error maps
 * to a domain case the Recovery screen can say something useful about -- an
 * error that arrives as a generic failure produces a generic sentence, and a
 * generic sentence on that screen is what makes it feel like a dead end.
 */
class Bip39PhraseValidatorTest {

    private val validator = Bip39PhraseValidator()

    @Test
    fun wordCount_is24() {
        assertThat(validator.wordCount).isEqualTo(24)
    }

    /**
     * The wordlist is lazy so it is not read during Hilt graph construction on
     * the main thread. [RecoveryPhraseValidator.warmUp] is what moves that read
     * to the IO dispatcher; it has to be repeatable and side-effect free.
     */
    @Test
    fun warmUp_isIdempotent() {
        validator.warmUp()
        validator.warmUp()

        assertThat(validator.isKnownWord("abandon")).isTrue()
    }

    @Test
    fun wrongLength_reportsBothCounts() {
        val result = validator.validate(List(12) { "abandon" })

        assertThat(result).isEqualTo(PhraseValidation.WrongWordCount(12, 24))
    }

    @Test
    fun unknownWord_reportsItsOneBasedPosition() {
        val words = MutableList(24) { "abandon" }
        words[4] = "notaword"

        val result = validator.validate(words)

        assertThat(result).isEqualTo(PhraseValidation.UnknownWord("notaword", 5))
    }

    /**
     * 24 real words that are not a valid mnemonic. This is the case worth
     * distinguishing: it is almost always two words transposed, and "check the
     * order" is actionable where "invalid phrase" is not.
     */
    @Test
    fun realWordsThatFailTheChecksum_reportChecksumMismatch() {
        val result = validator.validate(List(24) { "abandon" })

        assertThat(result).isEqualTo(PhraseValidation.ChecksumMismatch)
    }

    @Test
    fun suggestions_completeFromTheRealWordlist() {
        assertThat(validator.suggestions("aban")).containsExactly("abandon")
        assertThat(validator.suggestions("ab")).contains("ability")
    }

    @Test
    fun suggestions_areEmptyForAPrefixNoWordHas() {
        assertThat(validator.suggestions("qqq")).isEmpty()
    }

    @Test
    fun suggestions_areEmptyForBlankInput() {
        assertThat(validator.suggestions("")).isEmpty()
        assertThat(validator.suggestions("   ")).isEmpty()
    }

    @Test
    fun suggestions_respectTheLimit() {
        assertThat(validator.suggestions("a", limit = 3)).hasSize(3)
    }

    @Test
    fun isKnownWord_ignoresCaseAndSurroundingSpace() {
        assertThat(validator.isKnownWord("  Abandon ")).isTrue()
        assertThat(validator.isKnownWord("notaword")).isFalse()
    }

    /**
     * Users paste phrases out of password managers and notes apps, which means
     * newlines, tabs and capitals. None of that should read as a wrong phrase.
     */
    @Test
    fun parse_handlesMessyPastedInput() {
        val parsed = validator.parse("Abandon\nability\tABLE   about\r\n")

        assertThat(parsed).containsExactly("abandon", "ability", "able", "about").inOrder()
    }
}
