package com.ledgerflow.core.data.vault

import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.bip39.MnemonicCheck
import com.ledgerflow.core.crypto.bip39.MnemonicError
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RecoveryPhraseValidator] over `:core:crypto`'s BIP-39 implementation.
 *
 * The only interesting part is [suggestions]. The wordlist is chosen so that
 * **four characters uniquely identify every word**, which is why the Recovery
 * screen can offer useful completions after a very short prefix -- and why a
 * user who wrote down only the first four letters of each word can still
 * recover. Sorting exact-prefix matches ahead of the rest keeps the obvious
 * candidate first.
 */
@Singleton
public class Bip39PhraseValidator @Inject constructor() : RecoveryPhraseValidator {

    override val wordCount: Int = Bip39.WORD_COUNT

    /**
     * Lazy for the same reason as `FileWrappedDekStore.directory`: this is a
     * `@Singleton` built during Hilt graph construction on the main thread, and
     * loading the 2048-word list reads a resource out of the APK.
     *
     * `:core:crypto` already caches it behind its own `lazy`, so the cost is
     * paid once per process wherever the first touch happens.
     */
    private val words: List<String> by lazy { Bip39.words() }
    private val wordSet: Set<String> by lazy { words.toSet() }

    override fun warmUp() {
        // `lazy` is SYNCHRONIZED by default, so if a keystroke races this the
        // main thread blocks on the monitor rather than performing the read
        // itself -- which is the difference between a pause and a StrictMode kill.
        wordSet.size
    }

    override fun validate(words: List<String>): PhraseValidation =
        when (val check = Bip39.validate(words)) {
            MnemonicCheck.Valid -> PhraseValidation.Valid
            is MnemonicCheck.Invalid -> check.error.toValidation()
        }

    override fun isKnownWord(word: String): Boolean = word.trim().lowercase() in wordSet

    override fun suggestions(prefix: String, limit: Int): List<String> {
        val normalized = prefix.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return words.asSequence()
            .filter { it.startsWith(normalized) }
            .take(limit)
            .toList()
    }

    override fun parse(input: String): List<String> = Bip39.parse(input)

    private fun MnemonicError.toValidation(): PhraseValidation = when (this) {
        is MnemonicError.WrongWordCount -> PhraseValidation.WrongWordCount(actual, expected)
        is MnemonicError.UnknownWord -> PhraseValidation.UnknownWord(word, position)
        MnemonicError.ChecksumMismatch -> PhraseValidation.ChecksumMismatch
    }
}
