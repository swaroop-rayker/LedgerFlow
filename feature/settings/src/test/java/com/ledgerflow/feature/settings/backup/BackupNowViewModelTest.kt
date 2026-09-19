package com.ledgerflow.feature.settings.backup

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.backup.BackUpNowUseCase
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.testing.backup.FakeBackupRepository
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
 * "Back up now" (§16 Q23), the screen's side.
 *
 * Most of this is about **how long the 24 words live**: long enough to fix one
 * wrong word without retyping twenty-four, and not a moment after a backup
 * succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupNowViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val validator = FakeRecoveryPhraseValidator()
    private lateinit var repository: FakeBackupRepository

    private val done = BackupOutcome.Done(
        fileName = "ledgerflow-20260918-101500.lfbk",
        rows = 12,
        imagesWritten = 1,
        imagesAlreadyThere = 0,
        imagesUnreadable = 0,
        imagesFailed = 0,
        olderBackupsRemoved = 0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBackupRepository(outcome = done)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BackupNowViewModel(BackUpNowUseCase(validator, repository), repository, validator)

    private fun BackupNowViewModel.typeFullPhrase() {
        repeat(validator.wordCount) { onEvent(BackupNowEvent.WordCommitted("abandon")) }
    }

    @Test
    fun theButton_waitsForAFullPhrase() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(BackupNowEvent.WordCommitted("abandon"))

        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        assertThat(vm.state.value.canSubmit).isFalse()
        assertThat(repository.backUpCalls).isEmpty()
    }

    /** **The words are gone the moment the backup succeeds.** */
    @Test
    fun aSuccessfulBackup_clearsTheWords() = runTest(dispatcher) {
        val vm = viewModel()
        vm.typeFullPhrase()

        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.backUpCalls.single()).hasSize(validator.wordCount)
        assertThat(vm.state.value.entry.words).isEmpty()
        assertThat(vm.state.value.result).isEqualTo(done)
        assertThat(vm.state.value.isWorking).isFalse()
    }

    /** A wrong phrase keeps them, so one bad word is fixed in place. */
    @Test
    fun theWrongPhrase_keepsTheWordsForAFix() = runTest(dispatcher) {
        repository.outcome = BackupOutcome.NotThisVaultsPhrase
        val vm = viewModel()
        vm.typeFullPhrase()

        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
        assertThat(vm.state.value.result).isEqualTo(BackupOutcome.NotThisVaultsPhrase)
    }

    /** CLAUDE.md §7, through the real use case: a bad checksum never reaches the repository. */
    @Test
    fun aBadChecksum_neverReachesTheRepository() = runTest(dispatcher) {
        validator.forcedValidation = PhraseValidation.ChecksumMismatch
        val vm = viewModel()
        vm.typeFullPhrase()

        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.backUpCalls).isEmpty()
        assertThat(vm.state.value.result)
            .isEqualTo(BackupOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch))
    }

    /** Editing a word makes the last result about words no longer on screen. */
    @Test
    fun editingAWord_clearsTheStaleResult() = runTest(dispatcher) {
        repository.outcome = BackupOutcome.NotThisVaultsPhrase
        val vm = viewModel()
        vm.typeFullPhrase()
        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        vm.onEvent(BackupNowEvent.WordRemoved(3))

        assertThat(vm.state.value.result).isNull()
    }

    /** The picker is offered before the user types 24 words for nowhere. */
    @Test
    fun noFolder_isOfferedUpFront() = runTest(dispatcher) {
        repository.folderName = null

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.needsFolder).isTrue()
    }

    @Test
    fun choosingAFolder_recordsItAndStopsAsking() = runTest(dispatcher) {
        repository.folderName = null
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(BackupNowEvent.FolderChosen("content://tree/backups"))
        advanceUntilIdle()

        assertThat(repository.chosenFolders).containsExactly("content://tree/backups")
        assertThat(vm.state.value.needsFolder).isFalse()
    }

    /** Backing out of the picker changes nothing. */
    @Test
    fun cancellingThePicker_changesNothing() = runTest(dispatcher) {
        repository.folderName = null
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(BackupNowEvent.FolderChosen(null))
        advanceUntilIdle()

        assertThat(repository.chosenFolders).isEmpty()
        assertThat(vm.state.value.needsFolder).isTrue()
    }

    /**
     * A backup that finds the folder gone asks for one again, keeping the words
     * — including when it went **after** the screen opened, so the answer from
     * opening is stale (BUG27).
     */
    @Test
    fun aLostFolder_asksAgainAndKeepsTheWords() = runTest(dispatcher) {
        repository.outcome = BackupOutcome.NoBackupFolder
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.needsFolder).isFalse()
        repository.folderName = null
        vm.typeFullPhrase()

        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()

        assertThat(vm.state.value.needsFolder).isTrue()
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    /** Where backups go is on screen, so the user can see what "Change folder" would change. */
    @Test
    fun theChosenFolder_isNamed_andNothingIsAskedFor() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.folderName).isEqualTo("LedgerFlow backups")
        assertThat(vm.state.value.needsFolder).isFalse()
        assertThat(vm.state.value.folderChanged).isFalse()
    }

    /**
     * BUG27's second half: a folder can be changed without losing the old one
     * first — the only way to move backups to a cloud drive — and the screen
     * says the earlier backups stayed behind.
     */
    @Test
    fun bug27_changingTheFolder_recordsItAndSaysTheOldBackupsStay() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(BackupNowEvent.FolderChosen("content://drive/tree/LedgerFlow-drive"))
        advanceUntilIdle()

        assertThat(repository.chosenFolders).containsExactly("content://drive/tree/LedgerFlow-drive")
        assertThat(vm.state.value.folderName).isEqualTo("LedgerFlow-drive")
        assertThat(vm.state.value.folderChanged).isTrue()
    }

    /** Choosing a first folder is not a change: there are no earlier backups to mention. */
    @Test
    fun aFirstFolder_isNotReportedAsAChange() = runTest(dispatcher) {
        repository.folderName = null
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(BackupNowEvent.FolderChosen("content://tree/backups"))
        advanceUntilIdle()

        assertThat(vm.state.value.folderChanged).isFalse()
    }
}
