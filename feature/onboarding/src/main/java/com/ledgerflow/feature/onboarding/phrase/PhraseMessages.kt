package com.ledgerflow.feature.onboarding.phrase

import com.ledgerflow.core.domain.vault.PhraseValidation

/**
 * Why a typed phrase was refused, in the user's words — for the two screens in
 * this module that take the 24 words, Recovery and Restore.
 *
 * Not in `:core:ui`, which by design depends on no domain type. "Back up now"
 * in `:feature:settings` keeps its own copy of these sentences, since features
 * never depend on features.
 */
internal fun PhraseValidation.rejectionMessage(): String = when (this) {
    PhraseValidation.ChecksumMismatch ->
        "Every word is valid but the phrase isn't — two words are probably in the wrong order."
    is PhraseValidation.UnknownWord ->
        "Word $position (\"$word\") isn't in the recovery word list."
    is PhraseValidation.WrongWordCount ->
        "That's $actual words; a recovery phrase has $expected."
    PhraseValidation.Valid -> ""
}
