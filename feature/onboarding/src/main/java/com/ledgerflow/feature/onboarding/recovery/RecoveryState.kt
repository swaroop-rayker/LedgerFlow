package com.ledgerflow.feature.onboarding.recovery

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryReason

/**
 * The Recovery screen (SPEC.md §7.3 step 2).
 *
 * ADR-0011 dropped the passphrase wrap partly on the argument that this screen
 * would be genuinely good rather than a punishment. That makes its shape a
 * requirement: words are entered one at a time with autocomplete off the real
 * 2048-word list, each committed word is visible and individually removable, and
 * nothing here is ever destructive.
 */
@Immutable
public data class RecoveryUiState(
    /** Why the silent unlock did not work. Drives the explanation at the top. */
    val reason: RecoveryReason = RecoveryReason.KeystoreUnavailable,

    /** Words committed so far, in order. */
    val words: List<String> = emptyList(),

    /** The word currently being typed. Not yet part of [words]. */
    val draft: String = "",

    /** Autocomplete candidates for [draft], best-first. */
    val suggestions: List<String> = emptyList(),

    /** The expected length, from the validator rather than a hardcoded 24. */
    val requiredWordCount: Int = 0,

    val isWorking: Boolean = false,

    val failure: RecoveryFailure? = null,
) {
    /** Enables Recover. Checksum validation happens on submit, not here. */
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
}

/** Why an attempt failed, in the vocabulary the screen explains it in. */
public sealed interface RecoveryFailure {

    /** Structural or checksum problem. Caught before any KDF ran. */
    public data class PhraseRejected(val validation: PhraseValidation) : RecoveryFailure

    /**
     * A well-formed phrase that does not open this vault.
     *
     * Distinct from [PhraseRejected] because the remedy differs: not "look for a
     * typo" but "this phrase belongs to a different install".
     */
    public data object PhraseDidNotMatch : RecoveryFailure

    /** Everything else -- the database would not open, the blob is damaged. */
    public data class Other(val reason: RecoveryReason) : RecoveryFailure
}

public sealed interface RecoveryEvent {
    public data class DraftChanged(val value: String) : RecoveryEvent

    /** Commit the draft, or a tapped suggestion, as the next word. */
    public data class WordCommitted(val word: String) : RecoveryEvent

    /** Remove one committed word. Position is 0-based. */
    public data class WordRemoved(val index: Int) : RecoveryEvent

    /** Handles a pasted phrase in any shape: newlines, numbering, capitals. */
    public data class Pasted(val text: String) : RecoveryEvent

    public data object Submitted : RecoveryEvent
    public data object FailureDismissed : RecoveryEvent
}
