package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.usecase.RecoverVaultUseCase
import com.ledgerflow.core.domain.usecase.SeedDefaultTaxonomyUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.testing.backup.FakeRestoreRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryKitRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryPhraseValidator
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import com.ledgerflow.feature.onboarding.recovery.RecoveryEvent
import com.ledgerflow.feature.onboarding.recovery.RecoveryViewModel
import com.ledgerflow.feature.onboarding.restore.RestoreEvent
import com.ledgerflow.feature.onboarding.restore.RestoreViewModel
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BUG30 — the 24 words outlived the screen they were typed on.
 *
 * Found on the owner's phone running `TESTING.md` D10 (2026-09-19): leaving the
 * restore screen with Back and coming back showed the previous attempt's words
 * still in place. The three phrase screens outside the navigation graph —
 * onboarding, Recovery, restore — take their ViewModels from the activity, so
 * `onCleared`, which the restore and "Back up now" screens rely on to forget
 * the words, never runs while the app lives. A successful Recovery kept all 24
 * correct words in memory for the life of the process; onboarding kept the
 * phrase it generated, against its own KDoc.
 *
 * Each screen now forgets explicitly, at the moment its words are finished
 * with — and each keeps them after a *failure*, because a wrong attempt must be
 * fixable without retyping 24 words (ADR-0011's bargain).
 *
 * "Forget" is what a JVM app can do: drop every reference. A `String` cannot be
 * wiped in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Bug30_PhraseIsForgottenWhenItsScreenIsDoneTest {

    private val dispatcher = StandardTestDispatcher()
    private val validator = FakeRecoveryPhraseValidator()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Restore ─────────────────────────────────────────────────────────────

    private fun restoreViewModel(outcome: RestoreOutcome): RestoreViewModel {
        val repository = FakeRestoreRepository(outcome = outcome, backups = listOf(BACKUP))
        return RestoreViewModel(RestoreFromBackupUseCase(validator, repository), repository, validator)
    }

    private fun RestoreViewModel.chooseAndType() {
        onEvent(RestoreEvent.FolderChosen("content://tree/backups"))
        repeat(validator.wordCount) { onEvent(RestoreEvent.WordCommitted("abandon")) }
    }

    @Test
    fun bug30_leavingTheRestoreScreen_forgetsTheWordsAndTheBackup() = runTest(dispatcher) {
        val vm = restoreViewModel(RestoreOutcome.WrongPhrase)
        vm.chooseAndType()
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)

        vm.onEvent(RestoreEvent.Left)

        assertThat(vm.state.value.entry.words).isEmpty()
        assertThat(vm.state.value.entry.draft).isEmpty()
        assertThat(vm.state.value.source).isNull()
        assertThat(vm.state.value.result).isNull()
        assertThat(vm.state.value.entry.requiredWordCount).isEqualTo(validator.wordCount)
    }

    /** The answer to a running restore still has to land on the words it was about. */
    @Test
    fun leavingWhileARestoreRuns_changesNothing() = runTest(dispatcher) {
        val vm = restoreViewModel(RestoreOutcome.WrongPhrase)
        vm.chooseAndType()
        // The folder's listing is asynchronous; without it there is no backup to restore.
        advanceUntilIdle()
        vm.onEvent(RestoreEvent.Submitted)
        assertThat(vm.state.value.isWorking).isTrue()

        vm.onEvent(RestoreEvent.Left)
        advanceUntilIdle()

        assertThat(vm.state.value.result).isEqualTo(RestoreOutcome.WrongPhrase)
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    // ── Recovery ────────────────────────────────────────────────────────────

    private fun recoveryViewModel(result: VaultOutcome): RecoveryViewModel {
        val vault = FakeVaultRepository().also { it.unlockResult = result }
        return RecoveryViewModel(RecoverVaultUseCase(vault, validator), validator)
    }

    private fun RecoveryViewModel.submitFullPhrase() {
        onEvent(RecoveryEvent.Pasted(List(validator.wordCount) { "abandon" }.joinToString(" ")))
        onEvent(RecoveryEvent.Submitted)
    }

    @Test
    fun bug30_aSuccessfulRecovery_forgetsTheWords() = runTest(dispatcher) {
        val vm = recoveryViewModel(VaultOutcome.Unlocked)

        vm.submitFullPhrase()
        advanceUntilIdle()

        assertThat(vm.state.value.words).isEmpty()
        assertThat(vm.state.value.entry.draft).isEmpty()
    }

    @Test
    fun aFailedRecovery_stillKeepsTheWordsForAFix() = runTest(dispatcher) {
        val vm = recoveryViewModel(VaultOutcome.PhraseDidNotMatch)

        vm.submitFullPhrase()
        advanceUntilIdle()

        assertThat(vm.state.value.words).hasSize(validator.wordCount)
    }

    // ── Onboarding ──────────────────────────────────────────────────────────

    private lateinit var vault: FakeVaultRepository

    private fun onboardingViewModel(): OnboardingViewModel {
        vault = FakeVaultRepository()
        return OnboardingViewModel(
            initializeVault = InitializeVaultUseCase(
                vault = vault,
                seedDefaultTaxonomy = SeedDefaultTaxonomyUseCase(
                    FakeCategoryRepository(),
                    FakePaymentMethodRepository(),
                ),
            ),
            recoveryKit = FakeRecoveryKitRepository(),
            random = SecureRandom(),
            challengeRandom = Random(SEED),
            io = dispatcher,
        )
    }

    private fun OnboardingViewModel.walkTheGate() {
        generatePhraseAndContinue()
        dispatcher.scheduler.advanceUntilIdle()
        onEvent(OnboardingEvent.PhraseAcknowledged)
        val mnemonic = state.value.mnemonic
        state.value.challengePositions.forEachIndexed { index, position ->
            onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        onEvent(OnboardingEvent.ChallengeSubmitted)
        onEvent(OnboardingEvent.RecoveryKitDismissed)
        onEvent(OnboardingEvent.BackupLocationDeclined)
    }

    @Test
    fun bug30_finishingOnboarding_forgetsThePhrase() = runTest(dispatcher) {
        val vm = onboardingViewModel()

        vm.walkTheGate()
        advanceUntilIdle()

        assertThat(vault.initializeRequests.single().mnemonic).hasSize(PHRASE_WORDS)
        assertThat(vm.state.value.mnemonic).isEmpty()
        assertThat(vm.state.value.challengePositions).isEmpty()
        assertThat(vm.state.value.challengeAnswers.all { it.isEmpty() }).isTrue()
    }

    /** The error message promises the phrase is unchanged; it has to still be there. */
    @Test
    fun aFailedSetup_keepsThePhraseForTheRetry() = runTest(dispatcher) {
        val vm = onboardingViewModel()
        vault.initializeResult = VaultOutcome.Failed(RecoveryReason.DatabaseUnopenable)

        vm.walkTheGate()
        advanceUntilIdle()

        assertThat(vm.state.value.errorMessage).isNotNull()
        assertThat(vm.state.value.mnemonic).hasSize(PHRASE_WORDS)
    }

    private companion object {
        const val BACKUP = "ledgerflow-20260919-120253.lfbk"
        const val PHRASE_WORDS = 24
        const val SEED = 42
    }
}
