package com.ledgerflow.core.domain.vault

/**
 * The 24 words, as a user types them — one screen's worth of entry state and
 * the rules for changing it.
 *
 * Extracted from the Recovery screen's ViewModel when "Back up now" (§16 Q23)
 * became a second place the phrase is typed. Two copies of these rules would be
 * two chances for one screen to accept a 25-word paste the other refuses, or to
 * commit on a space in one and not the other; one value, used by both, is the
 * only way the screens stay the same. The rendering is `:core:ui`'s
 * `LfPhraseEntry`, which takes this state's fields as plain values.
 *
 * Immutable: every change returns a new entry. Nothing here holds the phrase
 * longer than the screen that owns it — **a caller that is done with the words
 * replaces its entry with [cleared]**, so the last copy on the heap is the one
 * the garbage collector is already free to take.
 */
public data class PhraseEntry(
    /** Words committed so far, in order. */
    val words: List<String> = emptyList(),

    /** The word currently being typed. Not yet part of [words]. */
    val draft: String = "",

    /** Autocomplete candidates for [draft], best-first. */
    val suggestions: List<String> = emptyList(),

    /** The expected length, from the validator rather than a hardcoded 24. */
    val requiredWordCount: Int = 0,
) {
    /** Enables the screen's action. Checksum validation happens on submit, not here. */
    public val isComplete: Boolean
        get() = words.size == requiredWordCount

    public val remaining: Int
        get() = (requiredWordCount - words.size).coerceAtLeast(0)

    /**
     * True when the draft is not a prefix of any real word.
     *
     * Surfaced as you type rather than at submit: catching "abandom" on the
     * third keystroke is a very different experience from catching it after all
     * 24 words are in and the checksum fails.
     */
    public val draftIsUnknown: Boolean
        get() = draft.isNotBlank() && suggestions.isEmpty()

    /**
     * A new draft. **A space or newline in it commits the word(s)**: typing a
     * phrase is muscle memory with spaces in it, and requiring a tap per word
     * would make 24 words feel like 48 actions.
     */
    public fun withDraft(value: String, validator: RecoveryPhraseValidator): PhraseEntry {
        if (value.any { it.isWhitespace() }) {
            val committed = validator.parse(value).fold(this) { entry, word -> entry.commit(word) }
            return committed.copy(draft = "", suggestions = emptyList())
        }
        val normalized = value.trim().lowercase()
        return copy(draft = normalized, suggestions = validator.suggestions(normalized))
    }

    /**
     * Appends [word] as the next word.
     *
     * Silently dropping extra words would make a 25-word paste look like it
     * worked, so past [requiredWordCount] this stops accepting and lets the
     * count show why.
     */
    public fun commit(word: String): PhraseEntry {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || words.size >= requiredWordCount) return this
        return copy(words = words + normalized, draft = "", suggestions = emptyList())
    }

    /** Removes one committed word. [index] is 0-based; out of range changes nothing. */
    public fun remove(index: Int): PhraseEntry {
        if (index !in words.indices) return this
        return copy(words = words.filterIndexed { i, _ -> i != index })
    }

    /**
     * **Replaces** the whole phrase rather than appending: someone pasting 24
     * words means "these are the words", and appending them to a half-typed
     * attempt produces a 30-word phrase and a confusing error.
     */
    public fun paste(text: String, validator: RecoveryPhraseValidator): PhraseEntry {
        val parsed = validator.parse(text)
        if (parsed.isEmpty()) return this
        return copy(words = parsed.take(requiredWordCount), draft = "", suggestions = emptyList())
    }

    /** No words, same length requirement. What a screen keeps once the words are used. */
    public fun cleared(): PhraseEntry = PhraseEntry(requiredWordCount = requiredWordCount)
}
