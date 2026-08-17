package com.ledgerflow.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.domain.vault.RecoveryKitRepository
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.feature.onboarding.di.ChallengeRandom
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
@HiltViewModel
public class OnboardingViewModel @Inject constructor(
    private val initializeVault: InitializeVaultUseCase,
    private val recoveryKit: RecoveryKitRepository,
    private val random: SecureRandom,
    @param:ChallengeRandom private val challengeRandom: Random,
    @param:IoDispatcher private val io: CoroutineDispatcher,
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

            is OnboardingEvent.RecoveryKitRequested,
            OnboardingEvent.RecoveryKitConfirmed,
            OnboardingEvent.RecoveryKitCancelled,
            OnboardingEvent.RecoveryKitPickerLaunched,
            is OnboardingEvent.RecoveryKitFileChosen,
            OnboardingEvent.RecoveryKitDismissed,
            -> onRecoveryKitEvent(event, step)

            is OnboardingEvent.BackupLocationGranted ->
                ifAt(OnboardingStep.BackupLocation, step) {
                    completeBackupLocation(granted = true, treeUri = event.uri)
                }

            OnboardingEvent.BackupLocationDeclined ->
                ifAt(OnboardingStep.BackupLocation, step) {
                    completeBackupLocation(granted = false, treeUri = null)
                }

            // Safe from any step: it clears a message, it does not advance.
            OnboardingEvent.ErrorDismissed -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private inline fun ifAt(required: OnboardingStep, current: OnboardingStep, action: () -> Unit) {
        if (current == required) action()
    }

    /**
     * The Recovery Kit sub-flow (D-07): request -> warn -> pick -> write.
     *
     * Split out of [onEvent] because six of its branches belong to one
     * interaction. The two housekeeping events -- cancel and picker-launched --
     * are deliberately *not* step-guarded: both only clear transient state, and
     * a picker result arriving after the step advanced must still be allowed to
     * tidy up rather than leaving a stale dialog behind.
     */
    private fun onRecoveryKitEvent(event: OnboardingEvent, step: OnboardingStep) {
        when (event) {
            is OnboardingEvent.RecoveryKitRequested ->
                ifAt(OnboardingStep.RecoveryKit, step) {
                    _state.update { it.copy(kitConfirmFormat = event.format) }
                }

            OnboardingEvent.RecoveryKitConfirmed ->
                ifAt(OnboardingStep.RecoveryKit, step) {
                    _state.update {
                        it.copy(kitConfirmFormat = null, kitPickerRequest = it.kitConfirmFormat)
                    }
                }

            OnboardingEvent.RecoveryKitCancelled ->
                _state.update { it.copy(kitConfirmFormat = null) }

            OnboardingEvent.RecoveryKitPickerLaunched ->
                _state.update { it.copy(kitPickerRequest = null) }

            is OnboardingEvent.RecoveryKitFileChosen ->
                ifAt(OnboardingStep.RecoveryKit, step) { writeRecoveryKit(event.uri) }

            OnboardingEvent.RecoveryKitDismissed ->
                ifAt(OnboardingStep.RecoveryKit, step) { completeRecoveryKit(saved = false) }

            else -> Unit
        }
    }

    private fun selectCurrency(code: String) {
        _state.update { it.copy(selectedCurrency = code) }
    }

    /** Filename offered to the SAF picker, so the screen does not invent one. */
    public fun suggestedKitFileName(format: RecoveryKitFormat): String =
        recoveryKit.suggestedFileName(format)

    /** Generates the phrase and moves to the display step. */
    public fun generatePhraseAndContinue() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            // `viewModelScope` is Dispatchers.Main.immediate, so this needs an
            // explicit hop: generating a mnemonic loads the 2048-word list from
            // the APK the first time, and that is a main-thread disk read.
            // StrictMode caught it; it had been here unnoticed since Phase 0.
            val mnemonic = withContext(io) { Bip39.generate(random) }
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

    /**
     * Writes the kit, then advances only if the bytes actually landed.
     *
     * A failed write that silently advanced would leave the user believing they
     * have a Recovery Kit they do not have -- which is worse than never offering
     * one, because they would stop transcribing on the strength of it.
     */
    private fun writeRecoveryKit(uri: String?) {
        // Null means they backed out of the system picker. Not an error, not a
        // dismissal of the step -- just nothing happened.
        if (uri == null) return
        val format = _state.value.kitConfirmFormat ?: RecoveryKitFormat.Text
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            val written = recoveryKit.write(uri, format, _state.value.mnemonic)
            _state.update { it.copy(isWorking = false) }
            if (written) {
                completeRecoveryKit(saved = true)
            } else {
                _state.update {
                    it.copy(errorMessage = "Couldn't write the Recovery Kit to that location.")
                }
            }
        }
    }

    private fun completeRecoveryKit(saved: Boolean) {
        _state.update {
            it.copy(recoveryKitSaved = saved, step = OnboardingStep.BackupLocation)
        }
    }

    /**
     * The last gate step, and the point at which the vault is actually created.
     *
     * Nothing has been written to disk before now -- no DEK, no wrapped blobs, no
     * database. That is deliberate: `VaultRepository.openOnLaunch` treats an
     * existing phrase wrap as "onboarding completed", so initialising earlier
     * (say, when the word challenge passes) would mean an app killed at this
     * step relaunches straight past steps 4 and 5. The gate would be bypassable
     * by force-stopping, which is exactly the kind of hole §7.4 exists to close.
     *
     * The cost is that a user killed mid-gate is issued a new phrase. That is the
     * right trade: a phrase they wrote down but never confirmed protects nothing.
     */
    private fun completeBackupLocation(granted: Boolean, treeUri: String?) {
        _state.update {
            it.copy(
                backupLocationGranted = granted,
                backupTreeUri = treeUri,
                step = OnboardingStep.Complete,
                isWorking = true,
            )
        }
        viewModelScope.launch {
            val outcome = initializeVault(
                VaultInitRequest(
                    mnemonic = _state.value.mnemonic,
                    baseCurrency = _state.value.selectedCurrency,
                    backupTreeUri = treeUri,
                ),
            )
            _state.update {
                it.copy(
                    isWorking = false,
                    errorMessage = when (outcome) {
                        VaultOutcome.Unlocked -> null
                        // Nothing was destroyed; the phrase is still on screen and
                        // the vault simply does not exist yet.
                        else -> "Couldn't finish setting up your ledger. " +
                            "Your recovery phrase is unchanged — try again."
                    },
                )
            }
        }
    }
}
