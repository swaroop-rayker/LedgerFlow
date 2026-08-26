package com.ledgerflow.feature.onboarding.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.RecoverVaultUseCase
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Recovery screen (SPEC.md §7.3 step 2).
 *
 * [validator] is injected as a port rather than behind a use case: it is a pure,
 * synchronous domain service, and wrapping `suggestions`/`isKnownWord` in
 * single-method classes would add types that forward one call and hide nothing.
 * The operation that actually does something -- deriving the key and opening the
 * database -- goes through [RecoverVaultUseCase], which is where the layering
 * earns its keep.
 */
@HiltViewModel
public class RecoveryViewModel @Inject constructor(
    private val recoverVault: RecoverVaultUseCase,
    private val validator: RecoveryPhraseValidator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        RecoveryUiState(requiredWordCount = validator.wordCount),
    )
    public val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    /** Set by the shell once the vault reports why it could not open silently. */
    public fun setReason(reason: RecoveryReason) {
        _state.update { it.copy(reason = reason) }
    }

    public fun onEvent(event: RecoveryEvent) {
        when (event) {
            is RecoveryEvent.DraftChanged -> onDraftChanged(event.value)
            is RecoveryEvent.WordCommitted -> commit(event.word)
            is RecoveryEvent.WordRemoved -> remove(event.index)
            is RecoveryEvent.Pasted -> paste(event.text)
            RecoveryEvent.Submitted -> submit()
            RecoveryEvent.FailureDismissed -> _state.update { it.copy(failure = null) }
        }
    }

    /**
     * A space or newline in the field commits the word.
     *
     * Typing a phrase is muscle memory with spaces in it; requiring a tap per
     * word would make 24 words feel like 48 actions.
     */
    private fun onDraftChanged(value: String) {
        if (value.any { it.isWhitespace() }) {
            val parts = validator.parse(value)
            when {
                parts.isEmpty() -> _state.update { it.copy(draft = "", suggestions = emptyList()) }
                else -> {
                    parts.forEach(::commit)
                    _state.update { it.copy(draft = "", suggestions = emptyList()) }
                }
            }
            return
        }

        val normalized = value.trim().lowercase()
        _state.update {
            it.copy(
                draft = normalized,
                suggestions = validator.suggestions(normalized),
                failure = null,
            )
        }
    }

    private fun commit(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        _state.update { current ->
            // Silently dropping extra words would make a 25-word paste look like
            // it worked. Stop accepting instead, and let the count show why.
            if (current.words.size >= current.requiredWordCount) return@update current
            current.copy(
                words = current.words + normalized,
                draft = "",
                suggestions = emptyList(),
                failure = null,
            )
        }
    }

    private fun remove(index: Int) {
        _state.update { current ->
            if (index !in current.words.indices) return@update current
            current.copy(
                words = current.words.filterIndexed { i, _ -> i != index },
                failure = null,
            )
        }
    }

    /**
     * Replaces the whole phrase rather than appending.
     *
     * Someone pasting 24 words means "these are the words", and appending them
     * to a half-typed attempt produces a 30-word phrase and a confusing error.
     */
    private fun paste(text: String) {
        val parsed = validator.parse(text)
        if (parsed.isEmpty()) return
        _state.update {
            it.copy(
                words = parsed.take(it.requiredWordCount),
                draft = "",
                suggestions = emptyList(),
                failure = null,
            )
        }
    }

    private fun submit() {
        val words = _state.value.words
        if (words.size != _state.value.requiredWordCount) return

        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, failure = null) }
            // The use case validates before the KDF; a bad checksum comes back
            // immediately rather than after 2048 rounds of HMAC-SHA512.
            val outcome = recoverVault(words)
            _state.update { current ->
                current.copy(
                    isWorking = false,
                    // On success the shell switches away from this screen off the
                    // vault's own state, so there is nothing to set here.
                    failure = outcome.toFailure(),
                )
            }
        }
    }

    private fun VaultOutcome.toFailure(): RecoveryFailure? = when (this) {
        VaultOutcome.Unlocked -> null
        is VaultOutcome.PhraseRejected -> RecoveryFailure.PhraseRejected(validation)
        VaultOutcome.PhraseDidNotMatch -> RecoveryFailure.PhraseDidNotMatch
        is VaultOutcome.Failed -> RecoveryFailure.Other(reason)

        // The phrase was right and the vault opened far enough to find a
        // pending schema upgrade that then could not proceed (§8.1). Nothing
        // about that is a *recovery* failure, and reporting it as one here
        // would leave the user retyping twenty-four correct words at a screen
        // that cannot help. The shell routes to the upgrade screen off the
        // vault's own state, so this reports no failure and lets it.
        is VaultOutcome.UpgradeBlocked -> null
    }
}
