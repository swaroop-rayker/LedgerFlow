package com.ledgerflow.core.testing.vault

import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.domain.vault.RecoveryKitRepository
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.domain.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A scriptable [VaultRepository].
 *
 * Fakes rather than mocks (CLAUDE.md §12): the interesting assertions here are
 * about *sequences* of state -- Working then Unlocked, a rejected phrase leaving
 * the previous state untouched -- and a fake that really holds state can be
 * asked about them. A mock can only be asked what it was called with.
 */
public class FakeVaultRepository(
    initial: VaultState = VaultState.NeedsOnboarding,
) : VaultRepository {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<VaultState> = _state

    /** Every state the vault passed through, in order, for sequence assertions. */
    public val emitted: MutableList<VaultState> = mutableListOf(initial)

    public var initializeResult: VaultOutcome = VaultOutcome.Unlocked
    public var unlockResult: VaultOutcome = VaultOutcome.Unlocked
    public var initializeRequests: MutableList<VaultInitRequest> = mutableListOf()
    public var phraseAttempts: MutableList<List<String>> = mutableListOf()
    public var openOnLaunchCalls: Int = 0

    public fun emit(next: VaultState) {
        _state.value = next
        emitted += next
    }

    override suspend fun openOnLaunch() {
        openOnLaunchCalls++
    }

    override suspend fun initialize(request: VaultInitRequest): VaultOutcome {
        initializeRequests += request
        if (initializeResult == VaultOutcome.Unlocked) emit(VaultState.Unlocked)
        return initializeResult
    }

    override suspend fun unlockWithPhrase(mnemonic: List<String>): VaultOutcome {
        phraseAttempts += mnemonic
        if (unlockResult == VaultOutcome.Unlocked) emit(VaultState.Unlocked)
        return unlockResult
    }
}

/**
 * A validator over a small synthetic wordlist.
 *
 * Deliberately not the real BIP-39 list: a UI test that needs a checksum-valid
 * 24-word phrase to exercise a button is testing the button, and coupling it to
 * real entropy makes the fixture unreadable. The real derivation is covered by
 * the golden vectors in `:core:crypto`, which is where it belongs.
 */
public class FakeRecoveryPhraseValidator(
    override val wordCount: Int = 24,
    public val vocabulary: List<String> = DEFAULT_VOCABULARY,
) : RecoveryPhraseValidator {

    /** Set to make [validate] fail even for a well-formed word list. */
    public var forcedValidation: PhraseValidation? = null

    /** Asserted by the vault tests: the real one must be warmed off the main thread. */
    public var warmUpCalls: Int = 0

    override fun warmUp() {
        warmUpCalls++
    }

    override fun validate(words: List<String>): PhraseValidation {
        val unknown = words.withIndex().firstOrNull { (_, word) -> word !in vocabulary }
        return when {
            forcedValidation != null -> requireNotNull(forcedValidation)
            words.size != wordCount -> PhraseValidation.WrongWordCount(words.size, wordCount)
            unknown != null -> PhraseValidation.UnknownWord(unknown.value, unknown.index + 1)
            else -> PhraseValidation.Valid
        }
    }

    override fun isKnownWord(word: String): Boolean = word in vocabulary

    override fun suggestions(prefix: String, limit: Int): List<String> =
        if (prefix.isBlank()) {
            emptyList()
        } else {
            vocabulary.filter { it.startsWith(prefix) }.take(limit)
        }

    override fun parse(input: String): List<String> =
        input.lowercase().split(" ", "\n", "\t", "\r").filter { it.isNotBlank() }

    private companion object {
        private val DEFAULT_VOCABULARY = listOf(
            "abandon", "ability", "able", "about", "above", "absent", "absorb",
            "abstract", "absurd", "abuse", "access", "accident", "zoo",
        )
    }
}

/** Records what would have been written, without touching the filesystem. */
public class FakeRecoveryKitRepository : RecoveryKitRepository {

    public var succeeds: Boolean = true
    public val written: MutableList<Triple<String, RecoveryKitFormat, List<String>>> = mutableListOf()

    override suspend fun write(
        uri: String,
        format: RecoveryKitFormat,
        mnemonic: List<String>,
    ): Boolean {
        if (succeeds) written += Triple(uri, format, mnemonic)
        return succeeds
    }

    override fun suggestedFileName(format: RecoveryKitFormat): String =
        "fake-kit.${format.extension}"
}
