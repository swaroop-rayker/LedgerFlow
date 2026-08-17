package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.testing.vault.FakeRecoveryKitRepository
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The onboarding gate (SPEC.md §7.4).
 *
 * The tests that matter here are the negative ones: that there is no route past
 * the word challenge except answering it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private lateinit var vault: FakeVaultRepository
    private lateinit var kit: FakeRecoveryKitRepository

    // Constructed directly, not through Hilt: a ViewModel that can only be
    // built by the DI container is a ViewModel whose tests need a container.
    // The seeded challengeRandom is what makes the challenge positions
    // reproducible; the phrase RNG is left real because nothing here asserts
    // against a specific mnemonic.
    private fun viewModel(): OnboardingViewModel {
        vault = FakeVaultRepository()
        kit = FakeRecoveryKitRepository()
        return OnboardingViewModel(
            initializeVault = InitializeVaultUseCase(vault),
            recoveryKit = kit,
            random = SecureRandom(),
            challengeRandom = Random(42),
            io = dispatcher,
        )
    }

    @Test
    fun startsAtCurrencySelectionWithInrDefault() {
        val vm = viewModel()

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.BaseCurrency)
        assertThat(vm.state.value.selectedCurrency).isEqualTo("INR")
    }

    @Test
    fun generatePhrase_produces24WordsAndMovesToDisplay() = runTest(dispatcher) {
        val vm = viewModel()

        vm.generatePhraseAndContinue()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.mnemonic).hasSize(24)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.PhraseDisplay)
        // Hidden until deliberately revealed -- no shoulder-surfing the phrase
        // the instant the screen appears.
        assertThat(vm.state.value.phraseRevealed).isFalse()
    }

    @Test
    fun generatePhrase_producesADifferentPhraseEachTime() = runTest(dispatcher) {
        val first = viewModel().also { it.generatePhraseAndContinue() }
        val second = viewModel().also { it.generatePhraseAndContinue() }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(first.state.value.mnemonic).isNotEqualTo(second.state.value.mnemonic)
    }

    private fun startedChallenge(): OnboardingViewModel {
        val vm = viewModel()
        vm.generatePhraseAndContinue()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onEvent(OnboardingEvent.PhraseAcknowledged)
        return vm
    }

    @Test
    fun challenge_asksForThreeDistinctPositions() = runTest(dispatcher) {
        val vm = startedChallenge()

        val state = vm.state.value
        assertThat(state.step).isEqualTo(OnboardingStep.WordChallenge)
        assertThat(state.challengePositions).hasSize(3)
        assertThat(state.challengePositions.toSet()).hasSize(3)
    }

    @Test
    fun challenge_correctAnswersAdvanceToRecoveryKit() = runTest(dispatcher) {
        val vm = startedChallenge()
        val mnemonic = vm.state.value.mnemonic

        vm.state.value.challengePositions.forEachIndexed { index, position ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RecoveryKit)
        assertThat(vm.state.value.challengeError).isFalse()
    }

    @Test
    fun challenge_wrongAnswersDoNotAdvance() = runTest(dispatcher) {
        val vm = startedChallenge()

        repeat(3) { index ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, "zoo"))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WordChallenge)
        assertThat(vm.state.value.challengeError).isTrue()
    }

    /**
     * A failed attempt must re-roll the positions. Otherwise the same three
     * slots can be brute-forced by trial and error, and the user reaches the
     * app without ever having written the phrase down -- which is precisely the
     * outcome §7.4 exists to prevent.
     */
    @Test
    fun challenge_failedAttemptReissuesNewPositionsAndClearsAnswers() = runTest(dispatcher) {
        val vm = startedChallenge()
        val firstPositions = vm.state.value.challengePositions

        repeat(3) { index -> vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, "zoo")) }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)

        assertThat(vm.state.value.challengeAnswers.all { it.isEmpty() }).isTrue()
        // Over repeated failures the positions must not be a fixed triple.
        val laterPositions = (0 until 10).map {
            repeat(3) { index -> vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, "zoo")) }
            vm.onEvent(OnboardingEvent.ChallengeSubmitted)
            vm.state.value.challengePositions
        }
        assertThat((laterPositions + listOf(firstPositions)).toSet().size).isAtLeast(2)
    }

    @Test
    fun challenge_partialAnswersAreNotAccepted() = runTest(dispatcher) {
        val vm = startedChallenge()
        val mnemonic = vm.state.value.mnemonic

        vm.onEvent(
            OnboardingEvent.ChallengeAnswerChanged(0, mnemonic[vm.state.value.challengePositions[0] - 1]),
        )
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WordChallenge)
    }

    /**
     * There is no event that skips the challenge. If someone ever adds one,
     * this test is what should stop them in review.
     */
    @Test
    fun noEventCanBypassTheWordChallenge() = runTest(dispatcher) {
        val vm = startedChallenge()

        // Every event other than a correct submission, fired at the challenge.
        listOf(
            OnboardingEvent.PhraseRevealed,
            OnboardingEvent.PhraseAcknowledged,
            OnboardingEvent.RecoveryKitDismissed,
            OnboardingEvent.BackupLocationDeclined,
            OnboardingEvent.ErrorDismissed,
            OnboardingEvent.CurrencySelected("USD"),
        ).forEach(vm::onEvent)

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WordChallenge)
    }

    @Test
    fun recoveryKitAndBackupLocation_canBeDeclinedButAreRecorded() = runTest(dispatcher) {
        val vm = startedChallenge()
        val mnemonic = vm.state.value.mnemonic
        vm.state.value.challengePositions.forEachIndexed { index, position ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)

        vm.onEvent(OnboardingEvent.RecoveryKitDismissed)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.BackupLocation)
        assertThat(vm.state.value.recoveryKitSaved).isFalse()

        vm.onEvent(OnboardingEvent.BackupLocationDeclined)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Complete)
        assertThat(vm.state.value.backupLocationGranted).isFalse()
    }

    @Test
    fun onboardingStep_neverGoesBackwards() {
        assertThat(OnboardingStep.BaseCurrency.next()).isEqualTo(OnboardingStep.PhraseDisplay)
        assertThat(OnboardingStep.WordChallenge.next()).isEqualTo(OnboardingStep.RecoveryKit)
        assertThat(OnboardingStep.Complete.next()).isEqualTo(OnboardingStep.Complete)
    }

    // ── The vault is created at the END of the gate, not partway through ────

    private fun atRecoveryKit(): OnboardingViewModel {
        val vm = startedChallenge()
        val mnemonic = vm.state.value.mnemonic
        vm.state.value.challengePositions.forEachIndexed { index, position ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)
        return vm
    }

    /**
     * The gate must not be escapable by force-stopping.
     *
     * `VaultRepository.openOnLaunch` treats an existing phrase wrap as "setup
     * finished", so if the vault were created when the word challenge passed, a
     * user who killed the app on the Recovery Kit step would relaunch straight
     * into the ledger -- having skipped steps 4 and 5 of §7.4. Nothing may touch
     * disk until the whole gate is satisfied.
     */
    @Test
    fun vaultIsNotCreatedUntilTheFinalGateStep() = runTest(dispatcher) {
        val vm = atRecoveryKit()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vault.initializeRequests).isEmpty()

        vm.onEvent(OnboardingEvent.RecoveryKitDismissed)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vault.initializeRequests).isEmpty()

        vm.onEvent(OnboardingEvent.BackupLocationDeclined)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vault.initializeRequests).hasSize(1)
    }

    @Test
    fun initialize_carriesTheChosenCurrencyPhraseAndBackupTree() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(OnboardingEvent.CurrencySelected("SGD"))
        vm.generatePhraseAndContinue()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onEvent(OnboardingEvent.PhraseAcknowledged)
        val mnemonic = vm.state.value.mnemonic
        vm.state.value.challengePositions.forEachIndexed { index, position ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)
        vm.onEvent(OnboardingEvent.RecoveryKitDismissed)
        vm.onEvent(OnboardingEvent.BackupLocationGranted("content://tree/backups"))
        dispatcher.scheduler.advanceUntilIdle()

        val request = vault.initializeRequests.single()
        assertThat(request.baseCurrency).isEqualTo("SGD")
        assertThat(request.mnemonic).isEqualTo(mnemonic)
        assertThat(request.backupTreeUri).isEqualTo("content://tree/backups")
    }

    // ── Recovery Kit (D-07) ─────────────────────────────────────────────────

    /**
     * The kit is plaintext, so the confirmation dialog *is* the mitigation.
     * A picker that opened straight from the button would skip it.
     */
    @Test
    fun recoveryKit_pickerOnlyOpensAfterTheWarningIsAccepted() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Text))
        assertThat(vm.state.value.kitConfirmFormat).isEqualTo(RecoveryKitFormat.Text)
        assertThat(vm.state.value.kitPickerRequest).isNull()

        vm.onEvent(OnboardingEvent.RecoveryKitConfirmed)
        assertThat(vm.state.value.kitConfirmFormat).isNull()
        assertThat(vm.state.value.kitPickerRequest).isEqualTo(RecoveryKitFormat.Text)
    }

    @Test
    fun recoveryKit_cancellingTheWarningOpensNoPicker() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Pdf))
        vm.onEvent(OnboardingEvent.RecoveryKitCancelled)

        assertThat(vm.state.value.kitConfirmFormat).isNull()
        assertThat(vm.state.value.kitPickerRequest).isNull()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RecoveryKit)
    }

    @Test
    fun recoveryKit_writesTheMnemonicAndAdvances() = runTest(dispatcher) {
        val vm = atRecoveryKit()
        val mnemonic = vm.state.value.mnemonic

        vm.onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Pdf))
        vm.onEvent(OnboardingEvent.RecoveryKitFileChosen("content://docs/kit.pdf"))
        dispatcher.scheduler.advanceUntilIdle()

        val (uri, format, words) = kit.written.single()
        assertThat(uri).isEqualTo("content://docs/kit.pdf")
        assertThat(format).isEqualTo(RecoveryKitFormat.Pdf)
        assertThat(words).isEqualTo(mnemonic)
        assertThat(vm.state.value.recoveryKitSaved).isTrue()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.BackupLocation)
    }

    /**
     * A failed write that advanced anyway would leave the user believing they
     * hold a Recovery Kit they do not hold -- and they would stop transcribing
     * on the strength of it.
     */
    @Test
    fun recoveryKit_failedWriteDoesNotAdvanceOrClaimSuccess() = runTest(dispatcher) {
        val vm = atRecoveryKit()
        kit.succeeds = false

        vm.onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Text))
        vm.onEvent(OnboardingEvent.RecoveryKitFileChosen("content://docs/kit.txt"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.recoveryKitSaved).isFalse()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RecoveryKit)
        assertThat(vm.state.value.errorMessage).isNotNull()
    }

    /** Backing out of the system picker is not an answer to the step. */
    @Test
    fun recoveryKit_cancellingThePickerLeavesTheStepUnanswered() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Text))
        vm.onEvent(OnboardingEvent.RecoveryKitFileChosen(null))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(kit.written).isEmpty()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RecoveryKit)
        assertThat(vm.state.value.errorMessage).isNull()
    }
}
