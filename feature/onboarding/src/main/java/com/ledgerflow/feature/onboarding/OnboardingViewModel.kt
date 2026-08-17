package com.ledgerflow.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.crypto.bip39.Bip39
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Drives the onboarding gate (SPEC.md §7.4).
 *
 * The phrase is generated here and held only in memory until the DEK is
 * wrapped. It is deliberately not persisted anywhere in plaintext -- the only
 * copies that should exist are the user's transcription and the Recovery Kit
 * they choose to save.
 *
 * Note the shape of [advance]: a step can only move forward, and only when its
 * own precondition is met. There is no `skipTo`, no `goToStep`, and no way for
 * a screen to jump the queue.
 */
public class OnboardingViewModel(
    private val random: SecureRandom = SecureRandom(),
    private val challengeRandom: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    public val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var challenge: WordChallenge? = null

    /**
     * Every step-advancing event is guarded by the step it belongs to.
     *
     * Without these guards a `RecoveryKitDismissed` fired while the user is
     * still on the word challenge walks the state machine straight to
     * [OnboardingStep.Complete] -- bypassing the one control that makes
     * permanent data loss structurally impossible (§7.4). That is not
     * hypothetical: it was the actual behaviour until
     * `noEventCanBypassTheWordChallenge` caught it. A stale event from a
     * recomposition or a back-stack pop is enough to trigger it.
     */
    public fun onEvent(event: OnboardingEvent) {
        val step = _state.value.step
        when (event) {
            is OnboardingEvent.CurrencySelected ->
                ifAt(OnboardingStep.BaseCurrency, step) { selectCurrency(event.code) }

            OnboardingEvent.PhraseRevealed ->
                ifAt(OnboardingStep.PhraseDisplay, step) {
                    _state.update { it.copy(phraseRevealed = true) }
                }

            OnboardingEvent.PhraseAcknowledged ->
                ifAt(OnboardingStep.PhraseDisplay, step) { startChallenge() }

            is OnboardingEvent.ChallengeAnswerChanged ->
                ifAt(OnboardingStep.WordChallenge, step) { updateAnswer(event.index, event.answer) }

            OnboardingEvent.ChallengeSubmitted ->
                ifAt(OnboardingStep.WordChallenge, step) { submitChallenge() }

            is OnboardingEvent.RecoveryKitSaved ->
                ifAt(OnboardingStep.RecoveryKit, step) { completeRecoveryKit(saved = true) }

            OnboardingEvent.RecoveryKitDismissed ->
                ifAt(OnboardingStep.RecoveryKit, step) { completeRecoveryKit(saved = false) }

            is OnboardingEvent.BackupLocationGranted ->
                ifAt(OnboardingStep.BackupLocation, step) { completeBackupLocation(granted = true) }

            OnboardingEvent.BackupLocationDeclined ->
                ifAt(OnboardingStep.BackupLocation, step) { completeBackupLocation(granted = false) }

            // Safe from any step: it clears a message, it does not advance.
            OnboardingEvent.ErrorDismissed -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private inline fun ifAt(required: OnboardingStep, current: OnboardingStep, action: () -> Unit) {
        if (current == required) action()
    }

    private fun selectCurrency(code: String) {
        _state.update { it.copy(selectedCurrency = code) }
    }

    /** Generates the phrase and moves to the display step. */
    public fun generatePhraseAndContinue() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            val mnemonic = Bip39.generate(random)
            _state.update {
                it.copy(
                    mnemonic = mnemonic,
                    step = OnboardingStep.PhraseDisplay,
                    phraseRevealed = false,
                    isWorking = false,
                )
            }
        }
    }

    private fun startChallenge() {
        val mnemonic = _state.value.mnemonic
        if (mnemonic.isEmpty()) {
            _state.update { it.copy(errorMessage = "No recovery phrase was generated.") }
            return
        }
        val created = WordChallenge.create(mnemonic, challengeRandom)
        challenge = created
        _state.update {
            it.copy(
                step = OnboardingStep.WordChallenge,
                challengePositions = created.positions,
                challengeAnswers = List(WordChallenge.CHALLENGE_COUNT) { "" },
                challengeError = false,
            )
        }
    }

    private fun updateAnswer(index: Int, answer: String) {
        _state.update { current ->
            current.copy(
                challengeAnswers = current.challengeAnswers.toMutableList().apply {
                    if (index in indices) this[index] = answer
                },
                // Clear the error as soon as they start correcting, rather than
                // leaving a red screen while they retype.
                challengeError = false,
            )
        }
    }

    private fun submitChallenge() {
        val current = challenge
        if (current == null) {
            _state.update { it.copy(errorMessage = "Challenge not started.") }
            return
        }
        if (current.isComplete(_state.value.challengeAnswers)) {
            _state.update { it.copy(step = OnboardingStep.RecoveryKit, challengeError = false) }
        } else {
            // Wrong answers re-issue a *new* challenge. Otherwise the user can
            // brute-force the same three slots by trial and error without ever
            // having written the phrase down.
            val reissued = WordChallenge.create(_state.value.mnemonic, challengeRandom)
            challenge = reissued
            _state.update {
                it.copy(
                    challengeError = true,
                    challengePositions = reissued.positions,
                    challengeAnswers = List(WordChallenge.CHALLENGE_COUNT) { "" },
                )
            }
        }
    }

    private fun completeRecoveryKit(saved: Boolean) {
        _state.update {
            it.copy(recoveryKitSaved = saved, step = OnboardingStep.BackupLocation)
        }
    }

    private fun completeBackupLocation(granted: Boolean) {
        _state.update {
            it.copy(backupLocationGranted = granted, step = OnboardingStep.Complete)
        }
    }
}
