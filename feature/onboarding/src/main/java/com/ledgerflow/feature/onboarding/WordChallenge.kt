package com.ledgerflow.feature.onboarding

import kotlin.random.Random

/**
 * The recovery-phrase word challenge (SPEC.md §7.4 step 3).
 *
 * Three randomly chosen positions must be re-entered before onboarding can
 * continue. **There is no skip, and there must never be one** (CLAUDE.md §7).
 * A "remind me later" here would defeat the entire durability design: the
 * phrase is the only factor that survives a factory reset, and a user who never
 * wrote it down does not know that until the day it is the only thing that
 * could have saved them.
 *
 * The friction is the feature.
 */
public class WordChallenge private constructor(
    /** 1-based positions, ascending, as shown to the user. */
    public val positions: List<Int>,
    private val expected: List<String>,
) {

    init {
        require(positions.size == CHALLENGE_COUNT) {
            "Challenge must ask for $CHALLENGE_COUNT words, was ${positions.size}"
        }
    }

    /**
     * Checks one answer. Comparison is trimmed and case-insensitive: the user
     * is transcribing from paper, and rejecting "Abandon" for "abandon" would
     * be punishing them for the keyboard's autocapitalisation, not for getting
     * it wrong.
     */
    public fun isCorrect(index: Int, answer: String): Boolean {
        require(index in positions.indices) { "No challenge slot at index $index" }
        return answer.trim().equals(expected[index], ignoreCase = true)
    }

    /** True only when all three are right. The gate is all-or-nothing. */
    public fun isComplete(answers: List<String>): Boolean =
        answers.size == CHALLENGE_COUNT &&
            answers.indices.all { isCorrect(it, answers[it]) }

    public companion object {
        public const val CHALLENGE_COUNT: Int = 3

        /**
         * Picks [CHALLENGE_COUNT] **distinct** positions from the mnemonic.
         *
         * Distinctness matters: asking for word 7 three times would look like a
         * challenge while verifying almost nothing.
         */
        public fun create(mnemonic: List<String>, random: Random = Random.Default): WordChallenge {
            require(mnemonic.size >= CHALLENGE_COUNT) {
                "Mnemonic too short for a challenge: ${mnemonic.size}"
            }
            val chosen = mnemonic.indices.shuffled(random).take(CHALLENGE_COUNT).sorted()
            return WordChallenge(
                positions = chosen.map { it + 1 },
                expected = chosen.map { mnemonic[it] },
            )
        }
    }
}
