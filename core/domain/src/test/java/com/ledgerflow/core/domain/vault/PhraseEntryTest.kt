package com.ledgerflow.core.domain.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How the 24 words are typed — the rules the Recovery screen and "Back up now"
 * now share. These used to be tested only through the Recovery ViewModel; they
 * are tested here because a second screen depends on them, and a regression
 * would otherwise surface on whichever screen was not being looked at.
 */
class PhraseEntryTest {

    /** Just enough of a validator: a six-word list, 4-word phrases. */
    private val validator = object : RecoveryPhraseValidator {
        val vocabulary = listOf("abandon", "ability", "able", "about", "above", "absent")
        override val wordCount: Int = 4
        override fun warmUp() = Unit
        override fun validate(words: List<String>): PhraseValidation = PhraseValidation.Valid
        override fun isKnownWord(word: String): Boolean = word in vocabulary
        override fun suggestions(prefix: String, limit: Int): List<String> =
            if (prefix.isBlank()) emptyList() else vocabulary.filter { it.startsWith(prefix) }.take(limit)
        override fun parse(input: String): List<String> =
            input.split(Regex("""[\s,.]+|\d+[.)]?""")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    private val empty = PhraseEntry(requiredWordCount = validator.wordCount)

    @Test
    fun typing_offersSuggestionsAndNormalises() {
        val entry = empty.withDraft("ABa", validator)

        assertThat(entry.draft).isEqualTo("aba")
        assertThat(entry.suggestions).containsExactly("abandon")
        assertThat(entry.draftIsUnknown).isFalse()
    }

    @Test
    fun aDraftThatIsNoWord_isFlaggedAsYouType() {
        val entry = empty.withDraft("xyz", validator)

        assertThat(entry.suggestions).isEmpty()
        assertThat(entry.draftIsUnknown).isTrue()
    }

    /** A space commits: 24 words should not cost 48 actions. */
    @Test
    fun aSpace_commitsTheWordAndClearsTheDraft() {
        val entry = empty.withDraft("abandon ", validator)

        assertThat(entry.words).containsExactly("abandon")
        assertThat(entry.draft).isEmpty()
        assertThat(entry.suggestions).isEmpty()
    }

    @Test
    fun aRemovedWord_leavesTheOthersInOrder() {
        val entry = empty.commit("abandon").commit("ability").commit("able").remove(1)

        assertThat(entry.words).containsExactly("abandon", "able").inOrder()
    }

    @Test
    fun removingOutOfRange_changesNothing() {
        val entry = empty.commit("abandon")

        assertThat(entry.remove(5)).isEqualTo(entry)
        assertThat(entry.remove(-1)).isEqualTo(entry)
    }

    /** Past the required count it stops accepting, so a 25-word paste cannot look like it worked. */
    @Test
    fun commits_stopAtTheRequiredCount() {
        val full = listOf("abandon", "ability", "able", "about").fold(empty) { e, w -> e.commit(w) }

        assertThat(full.isComplete).isTrue()
        assertThat(full.commit("above")).isEqualTo(full)
    }

    /** A paste means "these are the words" -- it replaces, never appends. */
    @Test
    fun aPaste_replacesAnyPartialEntryAndIsCappedAtTheCount() {
        val entry = empty.commit("absent").paste("1. abandon 2. ability\n3. able 4. about 5. above", validator)

        assertThat(entry.words).containsExactly("abandon", "ability", "able", "about").inOrder()
    }

    @Test
    fun aPasteOfNothing_changesNothing() {
        val entry = empty.commit("abandon")

        assertThat(entry.paste("   ", validator)).isEqualTo(entry)
    }

    @Test
    fun remaining_countsDown() {
        assertThat(empty.remaining).isEqualTo(4)
        assertThat(empty.commit("abandon").remaining).isEqualTo(3)
    }

    /** What a screen keeps once the words have been used: nothing but the length. */
    @Test
    fun cleared_dropsEveryWordAndKeepsTheLength() {
        val cleared = empty.commit("abandon").withDraft("abi", validator).cleared()

        assertThat(cleared).isEqualTo(PhraseEntry(requiredWordCount = validator.wordCount))
    }
}
