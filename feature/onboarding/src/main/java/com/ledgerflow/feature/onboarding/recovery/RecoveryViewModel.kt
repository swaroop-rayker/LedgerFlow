package com.ledgerflow.feature.onboarding.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.RecoverVaultUseCase
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.PhraseScan
import com.ledgerflow.core.domain.vault.applyScan
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
        RecoveryUiState(entry = PhraseEntry(requiredWordCount = validator.wordCount)),
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
            is RecoveryEvent.Scanner -> scanner(event)
        }
    }

    /**
     * Every word event goes through [PhraseEntry], the rules "Back up now"
     * shares, and any change to the words clears the previous failure: the
     * message was about words that are no longer what is on screen.
     */
    private fun onDraftChanged(value: String) = updateEntry { it.withDraft(value, validator) }

    private fun commit(word: String) = updateEntry { it.commit(word) }

    private fun remove(index: Int) = updateEntry { it.remove(index) }

    private fun paste(text: String) = updateEntry { it.paste(text, validator) }

    /**
     * A scanned Recovery Kit (ADR-0028), through the same [PhraseEntry] a typed
     * word goes through. A QR that is not ours leaves the camera open.
     */
    private fun scanner(event: RecoveryEvent.Scanner) {
        when (event) {
            RecoveryEvent.Scanner.Requested -> _state.update { it.copy(isScanning = true, scanMessage = null) }
            RecoveryEvent.Scanner.Dismissed -> _state.update { it.copy(isScanning = false) }
            is RecoveryEvent.Scanner.Read -> scanned(event.text)
        }
    }

    private fun scanned(text: String) {
        when (val scan = _state.value.entry.applyScan(text, validator)) {
            is PhraseScan.Filled ->
                _state.update {
                    it.copy(entry = scan.entry, isScanning = false, scanMessage = null, failure = null)
                }

            is PhraseScan.Rejected -> _state.update { it.copy(isScanning = false, scanMessage = scan.message) }
            PhraseScan.KeepLooking -> Unit
        }
    }

    private fun updateEntry(change: (PhraseEntry) -> PhraseEntry) {
        _state.update { current ->
            val entry = change(current.entry)
            if (entry == current.entry) current else current.copy(entry = entry, failure = null)
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
                    // BUG30: this ViewModel belongs to the activity and outlives
                    // the screen, so once the words have opened the vault they
                    // are dropped here — `onCleared` would not run for the life
                    // of the process. A failure keeps them for a fix.
                    entry = if (outcome.leavesTheScreen()) current.entry.cleared() else current.entry,
                )
            }
        }
    }

    /** The words did their job and the shell routes away: to the vault, or to the upgrade screen. */
    private fun VaultOutcome.leavesTheScreen(): Boolean = when (this) {
        VaultOutcome.Unlocked, is VaultOutcome.UpgradeBlocked -> true
        is VaultOutcome.PhraseRejected, VaultOutcome.PhraseDidNotMatch, is VaultOutcome.Failed -> false
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
