package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import kotlin.random.Random
import org.junit.Test

/**
 * The word challenge is the single control that makes "data permanently
 * unrecoverable" structurally impossible (SPEC.md §7.4). These tests exist to
 * make sure it cannot be weakened by accident.
 */
class WordChallengeTest {

    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 3 })

    @Test
    fun create_asksForThreeDistinctPositions() {
        repeat(200) { seed ->
            val challenge = WordChallenge.create(mnemonic, Random(seed))

            assertThat(challenge.positions).hasSize(WordChallenge.CHALLENGE_COUNT)
            // Distinct: asking for word 7 three times would look like a
            // challenge while verifying almost nothing.
            assertThat(challenge.positions.toSet()).hasSize(WordChallenge.CHALLENGE_COUNT)
        }
    }

    @Test
    fun create_positionsAreOneBasedAndWithinThePhrase() {
        repeat(200) { seed ->
            val challenge = WordChallenge.create(mnemonic, Random(seed))

            challenge.positions.forEach { position ->
                assertThat(position).isAtLeast(1)
                assertThat(position).isAtMost(mnemonic.size)
            }
        }
    }

    @Test
    fun create_positionsAreAscendingForTheUi() {
        repeat(50) { seed ->
            val challenge = WordChallenge.create(mnemonic, Random(seed))

            assertThat(challenge.positions).isInOrder()
        }
    }

    @Test
    fun create_overManyDrawsUsesMoreThanAHandfulOfPositions() {
        // Guards against a degenerate generator that always picks 1, 2, 3.
        val seen = (0 until 500)
            .flatMap { seed -> WordChallenge.create(mnemonic, Random(seed)).positions }
            .toSet()

        assertThat(seen.size).isAtLeast(mnemonic.size / 2)
    }

    @Test
    fun isComplete_acceptsTheCorrectWords() {
        val challenge = WordChallenge.create(mnemonic, Random(7))
        val answers = challenge.positions.map { mnemonic[it - 1] }

        assertThat(challenge.isComplete(answers)).isTrue()
    }

    @Test
    fun isComplete_isCaseInsensitiveAndTrimmed() {
        val challenge = WordChallenge.create(mnemonic, Random(7))
        // The user is transcribing from paper; the keyboard capitalises and
        // fingers add spaces. Neither is a wrong answer.
        val answers = challenge.positions.map { "  ${mnemonic[it - 1].uppercase()} " }

        assertThat(challenge.isComplete(answers)).isTrue()
    }

    @Test
    fun isComplete_rejectsASingleWrongWord() {
        val challenge = WordChallenge.create(mnemonic, Random(7))
        val answers = challenge.positions
            .map { mnemonic[it - 1] }
            .toMutableList()
            .apply { this[1] = "zoo" }

        assertThat(challenge.isComplete(answers)).isFalse()
    }

    @Test
    fun isComplete_rejectsBlanksAndShortSubmissions() {
        val challenge = WordChallenge.create(mnemonic, Random(7))

        assertThat(challenge.isComplete(listOf("", "", ""))).isFalse()
        assertThat(challenge.isComplete(listOf(mnemonic[challenge.positions[0] - 1]))).isFalse()
        assertThat(challenge.isComplete(emptyList())).isFalse()
    }

    @Test
    fun isComplete_rejectsRightWordsInTheWrongSlots() {
        val challenge = WordChallenge.create(mnemonic, Random(11))
        val correct = challenge.positions.map { mnemonic[it - 1] }
        val rotated = listOf(correct[2], correct[0], correct[1])

        // Only meaningful when the three words are not identical, which the
        // zero-entropy-style phrases can produce.
        if (correct.toSet().size == WordChallenge.CHALLENGE_COUNT) {
            assertThat(challenge.isComplete(rotated)).isFalse()
        }
    }

    @Test
    fun create_rejectsAPhraseTooShortToChallenge() {
        val error = runCatching { WordChallenge.create(listOf("abandon", "art")) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
