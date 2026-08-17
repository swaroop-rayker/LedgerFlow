package com.ledgerflow.core.domain.vault

/**
 * The outcome of the *cheap* phrase check -- structure and checksum only.
 *
 * This runs on every keystroke. It must never trigger the KDF: 2048 rounds of
 * HMAC-SHA512 on a phrase with a typo in it costs real time and presents as a
 * frozen Recovery screen, which is the one screen where the user is already
 * worried (CLAUDE.md §7).
 */
public sealed interface PhraseValidation {

    public data object Valid : PhraseValidation

    /** Fewer or more than 24 words. Carries both numbers so the UI can count down. */
    public data class WrongWordCount(val actual: Int, val expected: Int) : PhraseValidation

    /** Not in the BIP-39 English wordlist. [position] is 1-based, for display. */
    public data class UnknownWord(val word: String, val position: Int) : PhraseValidation

    /**
     * Every word is real but the phrase as a whole is not valid -- almost always
     * two words swapped, or one correct-but-wrong word. Worth saying so, because
     * "check the order" is actionable and "invalid phrase" is not.
     */
    public data object ChecksumMismatch : PhraseValidation
}

/**
 * BIP-39 knowledge, as a port.
 *
 * The wordlist lives in `:core:crypto`, which `:core:domain` may not depend on.
 * The Recovery screen needs it anyway -- autocomplete off the real 2048 words is
 * what makes typing 24 of them tolerable, and ADR-0011 made that screen's
 * quality a stated requirement rather than a nicety.
 */
public interface RecoveryPhraseValidator {

    /** 24 for LedgerFlow. Exposed so the UI never hardcodes it. */
    public val wordCount: Int

    /**
     * Loads whatever the implementation needs before its first real call.
     *
     * Not ceremony: the BIP-39 wordlist is 2048 entries read out of the APK, and
     * every natural first touch is on the main thread -- a keystroke on the
     * Recovery screen, or validating a submitted phrase. StrictMode kills the
     * debug build for exactly that (correctly), so the vault calls this from the
     * IO dispatcher at launch and every later call is pure memory.
     *
     * Idempotent, and safe to call from any thread.
     */
    public fun warmUp()

    /** Cheap. Safe to call on every keystroke. */
    public fun validate(words: List<String>): PhraseValidation

    /** True if [word] is in the wordlist at all -- per-word feedback as they type. */
    public fun isKnownWord(word: String): Boolean

    /** Autocomplete candidates for a partial word, best-first, at most [limit]. */
    public fun suggestions(prefix: String, limit: Int = DEFAULT_SUGGESTIONS): List<String>

    /**
     * Splits pasted input into words. Users paste with newlines, numbering and
     * stray capitals; none of that should read as a wrong phrase.
     */
    public fun parse(input: String): List<String>

    public companion object {
        public const val DEFAULT_SUGGESTIONS: Int = 4
    }
}
