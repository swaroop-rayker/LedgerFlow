package com.ledgerflow.feature.onboarding.restore

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.testing.backup.FakeRestoreRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryPhraseValidator
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
 * Restore from a backup (§16 Q11), the screen's side: choosing the backup, how
 * long the words live, and that a finished restore is final until the user
 * opens it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val validator = FakeRecoveryPhraseValidator()
    private lateinit var repository: FakeRestoreRepository

    private val newest = "ledgerflow-20260918-101500.lfbk"
    private val older = "ledgerflow-20260911-093000.lfbk"
    private val done = RestoreOutcome.Done(
        rows = 40,
        imagesRestored = 2,
        imagesNotFound = 1,
        imagesUnreadable = 0,
        imagesFailed = 0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRestoreRepository(outcome = done, backups = listOf(newest, older))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RestoreViewModel(RestoreFromBackupUseCase(validator, repository), repository, validator)

    private fun RestoreViewModel.typeFullPhrase() {
        repeat(validator.wordCount) { onEvent(RestoreEvent.WordCommitted("abandon")) }
    }

    private fun RestoreViewModel.chooseFolder() {
        onEvent(RestoreEvent.FolderChosen("content://tree/backups"))
    }

    /** The newest backup is the default, since name order is time order. */
    @Test
    fun choosingAFolder_listsItsBackupsWithTheNewestSelected() = runTest(dispatcher) {
        val vm = viewModel()

        vm.chooseFolder()
        advanceUntilIdle()

        assertThat(vm.state.value.backups).containsExactly(newest, older).inOrder()
        assertThat(vm.state.value.source).isEqualTo(RestoreSource.InFolder("content://tree/backups", newest))
    }

    @Test
    fun anotherBackup_canBeChosen_butOnlyOneThatIsListed() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()

        vm.onEvent(RestoreEvent.BackupSelected(older))
        vm.onEvent(RestoreEvent.BackupSelected("not-in-the-folder.lfbk"))

        assertThat(vm.state.value.selectedBackup).isEqualTo(older)
    }

    @Test
    fun anUnreadableFolder_saysSo_andOffersNothingToRestore() = runTest(dispatcher) {
        repository.backups = null
        val vm = viewModel()

        vm.chooseFolder()
        advanceUntilIdle()

        assertThat(vm.state.value.folderUnreadable).isTrue()
        assertThat(vm.state.value.source).isNull()
    }

    @Test
    fun aSingleFile_replacesTheFolder() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()

        vm.onEvent(RestoreEvent.SingleFileChosen("content://doc/one.lfbk"))

        assertThat(vm.state.value.source).isEqualTo(RestoreSource.SingleFile("content://doc/one.lfbk"))
        assertThat(vm.state.value.backups).isEmpty()
    }

    /** Backing out of either picker changes nothing. */
    @Test
    fun cancellingAPicker_changesNothing() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(RestoreEvent.FolderChosen(null))
        vm.onEvent(RestoreEvent.SingleFileChosen(null))
        advanceUntilIdle()

        assertThat(repository.listedFolders).isEmpty()
        assertThat(vm.state.value.source).isNull()
    }

    @Test
    fun theButton_waitsForABackupAndAFullPhrase() = runTest(dispatcher) {
        val vm = viewModel()
        vm.typeFullPhrase()
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()
        assertThat(repository.restoreCalls).isEmpty()

        vm.chooseFolder()
        advanceUntilIdle()
        vm.onEvent(RestoreEvent.WordRemoved(0))
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.restoreCalls).isEmpty()
        assertThat(vm.state.value.canSubmit).isFalse()
    }

    /** **The words are gone the moment the restore succeeds.** */
    @Test
    fun aSuccessfulRestore_clearsTheWords_andHandsOverExactlyWhatWasChosen() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()

        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        val (source, words) = repository.restoreCalls.single()
        assertThat(source).isEqualTo(RestoreSource.InFolder("content://tree/backups", newest))
        assertThat(words).hasSize(validator.wordCount)
        assertThat(vm.state.value.entry.words).isEmpty()
        assertThat(vm.state.value.result).isEqualTo(done)
    }

    /** The wrong words keep them, so one bad word is fixed in place. */
    @Test
    fun theWrongPhrase_keepsTheWordsForAFix() = runTest(dispatcher) {
        repository.outcome = RestoreOutcome.WrongPhrase
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()

        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
        assertThat(vm.state.value.result).isEqualTo(RestoreOutcome.WrongPhrase)
    }

    /** CLAUDE.md §7, through the real use case. */
    @Test
    fun aBadChecksum_neverReachesTheRepository() = runTest(dispatcher) {
        validator.forcedValidation = PhraseValidation.ChecksumMismatch
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()

        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.restoreCalls).isEmpty()
        assertThat(vm.state.value.result)
            .isEqualTo(RestoreOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch))
    }

    /**
     * The restored vault opens only when the user has read the report — and a
     * finished restore cannot be restarted underneath it by a stale tap.
     */
    @Test
    fun afterARestore_onlyContinueDoesAnything() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()
        assertThat(repository.finishCalls).isEqualTo(0)

        vm.onEvent(RestoreEvent.SingleFileChosen("content://doc/other.lfbk"))
        vm.typeFullPhrase()
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()
        assertThat(repository.restoreCalls).hasSize(1)
        assertThat(vm.state.value.result).isEqualTo(done)

        vm.onEvent(RestoreEvent.Continued)
        advanceUntilIdle()
        assertThat(repository.finishCalls).isEqualTo(1)
    }

    /** Continue before a restore has finished opens nothing. */
    @Test
    fun continueBeforeARestore_opensNothing() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(RestoreEvent.Continued)
        advanceUntilIdle()

        assertThat(repository.finishCalls).isEqualTo(0)
    }

    @Test
    fun editingAWord_clearsTheStaleResult() = runTest(dispatcher) {
        repository.outcome = RestoreOutcome.WrongPhrase
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        vm.onEvent(RestoreEvent.WordRemoved(3))

        assertThat(vm.state.value.result).isNull()
    }
}
