package com.ledgerflow.feature.onboarding.recovery

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.vault.PhraseEntry
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

    /**
     * The words as typed. Shared with "Back up now" through [PhraseEntry], so
     * the two screens cannot drift apart on how a phrase is entered.
     */
    val entry: PhraseEntry = PhraseEntry(),
    /** The QR scanner is open (ADR-0028). Typing stays available behind it. */
    val isScanning: Boolean = false,
    /** Why the last scan was refused, if it was. Cleared by the next scan or edit. */
    val scanMessage: String? = null,

    val isWorking: Boolean = false,

    val failure: RecoveryFailure? = null,
) {
    /** Words committed so far, in order. */
    public val words: List<String> get() = entry.words

    /** The word currently being typed. Not yet part of [words]. */
    public val draft: String get() = entry.draft

    /** Autocomplete candidates for [draft], best-first. */
    public val suggestions: List<String> get() = entry.suggestions

    /** The expected length, from the validator rather than a hardcoded 24. */
    public val requiredWordCount: Int get() = entry.requiredWordCount

    /** Enables Recover. Checksum validation happens on submit, not here. */
    public val isComplete: Boolean get() = entry.isComplete

    public val remaining: Int get() = entry.remaining

    /** See [PhraseEntry.draftIsUnknown]. */
    public val draftIsUnknown: Boolean get() = entry.draftIsUnknown
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

    /**
     * The Recovery Kit scanner (ADR-0028), grouped so the screen's `when` has
     * one branch for "the camera said something" rather than three.
     */
    public sealed interface Scanner : RecoveryEvent {
        /** Open the scanner. Typing stays available behind it. */
        public data object Requested : Scanner

        /** Close it without a scan — "type the words instead", or back. */
        public data object Dismissed : Scanner

        /** A QR code was read; the text is validated before it becomes words. */
        public data class Read(val text: String) : Scanner
    }
    public data object FailureDismissed : RecoveryEvent
}
