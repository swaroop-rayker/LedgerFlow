package com.ledgerflow.feature.onboarding.recovery

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.RecoverVaultUseCase
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.testing.vault.FakeRecoveryPhraseValidator
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Recovery screen (SPEC.md §7.3 step 2).
 *
 * ADR-0011 dropped the passphrase wrap on the argument that typing 24 words
 * would be made tolerable rather than punishing. These tests are what hold that
 * argument to account: entry has to survive pasting, correcting, and getting it
 * wrong, and none of those may cost the user their attempt.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vault: FakeVaultRepository
    private val validator = FakeRecoveryPhraseValidator()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): RecoveryViewModel {
        vault = FakeVaultRepository()
        return RecoveryViewModel(RecoverVaultUseCase(vault, validator), validator)
    }

    private fun fullPhrase() = List(validator.wordCount) { "abandon" }

    @Test
    fun typingAWord_offersSuggestionsFromTheWordlist() {
        val vm = viewModel()

        vm.onEvent(RecoveryEvent.DraftChanged("ab"))

        assertThat(vm.state.value.suggestions).contains("abandon")
        assertThat(vm.state.value.draftIsUnknown).isFalse()
    }

    /**
     * Catching a bad word on the third keystroke is a completely different
     * experience from catching it after all 24 are in and the checksum fails.
     */
    @Test
    fun typingSomethingThatIsNotAWord_isFlaggedImmediately() {
        val vm = viewModel()

        vm.onEvent(RecoveryEvent.DraftChanged("qqq"))

        assertThat(vm.state.value.suggestions).isEmpty()
        assertThat(vm.state.value.draftIsUnknown).isTrue()
    }

    @Test
    fun whitespaceCommitsTheWord() {
        val vm = viewModel()

        vm.onEvent(RecoveryEvent.DraftChanged("abandon "))

        assertThat(vm.state.value.words).containsExactly("abandon")
        assertThat(vm.state.value.draft).isEmpty()
    }

    @Test
    fun tappingASuggestionCommitsIt() {
        val vm = viewModel()

        vm.onEvent(RecoveryEvent.DraftChanged("abi"))
        vm.onEvent(RecoveryEvent.WordCommitted("ability"))

        assertThat(vm.state.value.words).containsExactly("ability")
        assertThat(vm.state.value.suggestions).isEmpty()
    }

    @Test
    fun aCommittedWordCanBeRemovedByPosition() {
        val vm = viewModel()
        vm.onEvent(RecoveryEvent.DraftChanged("abandon ability able "))

        vm.onEvent(RecoveryEvent.WordRemoved(1))

        assertThat(vm.state.value.words).containsExactly("abandon", "able").inOrder()
    }

    /**
     * Pasting means "these are the words", so it replaces rather than appends.
     * Appending onto a half-typed attempt yields a 30-word phrase and an error
     * the user cannot explain.
     */
    @Test
    fun pastingReplacesAnyPartialEntry() {
        val vm = viewModel()
        vm.onEvent(RecoveryEvent.DraftChanged("abandon ability "))

        vm.onEvent(RecoveryEvent.Pasted(fullPhrase().joinToString(" ")))

        assertThat(vm.state.value.words).hasSize(validator.wordCount)
    }

    @Test
    fun entryStopsAtTheRequiredWordCount() {
        val vm = viewModel()

        vm.onEvent(RecoveryEvent.Pasted((fullPhrase() + listOf("zoo", "zoo")).joinToString(" ")))
        vm.onEvent(RecoveryEvent.WordCommitted("zoo"))

        assertThat(vm.state.value.words).hasSize(validator.wordCount)
        assertThat(vm.state.value.isComplete).isTrue()
    }

    @Test
    fun submit_isIgnoredUntilThePhraseIsComplete() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RecoveryEvent.DraftChanged("abandon "))

        vm.onEvent(RecoveryEvent.Submitted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.phraseAttempts).isEmpty()
    }

    @Test
    fun submit_completePhraseReachesTheVault() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RecoveryEvent.Pasted(fullPhrase().joinToString(" ")))

        vm.onEvent(RecoveryEvent.Submitted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.phraseAttempts).hasSize(1)
        assertThat(vm.state.value.failure).isNull()
        assertThat(vm.state.value.isWorking).isFalse()
    }

    /**
     * A checksum failure must be reported *without* the vault being asked --
     * that is the "validate before the KDF" rule, and the reason a typo comes
     * back instantly instead of after 2048 rounds of HMAC-SHA512.
     */
    @Test
    fun submit_invalidPhraseNeverReachesTheKdf() = runTest(dispatcher) {
        val vm = viewModel()
        validator.forcedValidation = PhraseValidation.ChecksumMismatch
        vm.onEvent(RecoveryEvent.Pasted(fullPhrase().joinToString(" ")))

        vm.onEvent(RecoveryEvent.Submitted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.phraseAttempts).isEmpty()
        assertThat(vm.state.value.failure)
            .isEqualTo(RecoveryFailure.PhraseRejected(PhraseValidation.ChecksumMismatch))
    }

    /**
     * A wrong phrase must leave every word the user typed exactly where it is.
     * Clearing the field on failure would mean re-entering 24 words to fix one.
     */
    @Test
    fun submit_wrongPhraseKeepsTheTypedWords() = runTest(dispatcher) {
        val vm = viewModel()
        vault = FakeVaultRepository().also { it.unlockResult = VaultOutcome.PhraseDidNotMatch }
        val subject = RecoveryViewModel(RecoverVaultUseCase(vault, validator), validator)
        subject.onEvent(RecoveryEvent.Pasted(fullPhrase().joinToString(" ")))

        subject.onEvent(RecoveryEvent.Submitted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.failure).isEqualTo(RecoveryFailure.PhraseDidNotMatch)
        assertThat(subject.state.value.words).hasSize(validator.wordCount)
    }

    /** Editing after a failure clears the message rather than leaving it stale. */
    @Test
    fun editingAfterAFailureClearsTheMessage() = runTest(dispatcher) {
        val vm = viewModel()
        validator.forcedValidation = PhraseValidation.ChecksumMismatch
        vm.onEvent(RecoveryEvent.Pasted(fullPhrase().joinToString(" ")))
        vm.onEvent(RecoveryEvent.Submitted)
        dispatcher.scheduler.advanceUntilIdle()

        vm.onEvent(RecoveryEvent.WordRemoved(0))

        assertThat(vm.state.value.failure).isNull()
    }
}
