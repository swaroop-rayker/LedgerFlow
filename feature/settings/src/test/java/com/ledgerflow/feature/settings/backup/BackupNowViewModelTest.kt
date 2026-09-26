package com.ledgerflow.feature.settings.backup

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.backup.BackUpNowUseCase
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseQr
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.testing.backup.FakeBackupRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryKitRepository
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
    private val kit = FakeRecoveryKitRepository()

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

    private fun viewModel() =
        BackupNowViewModel(BackUpNowUseCase(validator, repository), repository, validator, kit)

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

    // ─── Scanning a Recovery Kit (ADR-0028) ─────────────────────────────────

    @Test
    fun scanningAKit_fillsTheWordsAndClosesTheScanner() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(BackupNowEvent.Scanner.Requested)

        vm.onEvent(BackupNowEvent.Scanner.Read(KIT_CODE))

        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
        assertThat(vm.state.value.isScanning).isFalse()
    }

    @Test
    fun scanningSomeoneElsesCode_keepsTheCameraOpen() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(BackupNowEvent.Scanner.Requested)

        vm.onEvent(BackupNowEvent.Scanner.Read("upi://pay?pa=someone@upi"))

        assertThat(vm.state.value.isScanning).isTrue()
        assertThat(vm.state.value.entry.words).isEmpty()
    }


    // ── A new Recovery Kit from the verified words (ADR-0028, amended 2026-09-26) ──

    private suspend fun kotlinx.coroutines.test.TestScope.backedUp(): BackupNowViewModel {
        val vm = viewModel()
        vm.typeFullPhrase()
        vm.onEvent(BackupNowEvent.Submitted)
        advanceUntilIdle()
        return vm
    }

    private fun BackupNowViewModel.saveKitTo(uri: String?) {
        onEvent(BackupNowEvent.Kit.Requested)
        onEvent(BackupNowEvent.Kit.Confirmed)
        onEvent(BackupNowEvent.Kit.PickerLaunched)
        onEvent(BackupNowEvent.Kit.FileChosen(uri))
    }

    @Test
    fun aSuccessfulBackup_offersANewKit() = runTest(dispatcher) {
        val vm = backedUp()

        assertThat(vm.state.value.kitOffered).isTrue()
        assertThat(vm.state.value.entry.words).isEmpty()
    }

    @Test
    fun aRefusedBackup_offersNoKit_andAskingDoesNothing() = runTest(dispatcher) {
        repository.outcome = BackupOutcome.NotThisVaultsPhrase
        val vm = backedUp()

        vm.saveKitTo("content://kit")
        advanceUntilIdle()

        assertThat(vm.state.value.kitOffered).isFalse()
        assertThat(vm.state.value.kitConfirming).isFalse()
        assertThat(kit.written).isEmpty()
    }

    /** The warning first, then the picker, then a PDF of exactly the verified words. */
    @Test
    fun savingTheKit_warnsThenWritesAPdfOfTheVerifiedWords_thenForgetsThem() = runTest(dispatcher) {
        val vm = backedUp()

        vm.onEvent(BackupNowEvent.Kit.Requested)
        assertThat(vm.state.value.kitConfirming).isTrue()
        assertThat(vm.state.value.kitPickerRequested).isFalse()
        vm.onEvent(BackupNowEvent.Kit.Confirmed)
        assertThat(vm.state.value.kitPickerRequested).isTrue()
        vm.onEvent(BackupNowEvent.Kit.PickerLaunched)
        assertThat(vm.state.value.kitPickerRequested).isFalse()
        vm.onEvent(BackupNowEvent.Kit.FileChosen("content://kit"))
        advanceUntilIdle()

        val (uri, format, words) = kit.written.single()
        assertThat(uri).isEqualTo("content://kit")
        assertThat(format).isEqualTo(RecoveryKitFormat.Pdf)
        assertThat(words).isEqualTo(repository.backUpCalls.single())
        assertThat(vm.state.value.kitSaved).isTrue()
        assertThat(vm.state.value.kitOffered).isFalse()

        // Forgotten: a second pick writes nothing.
        vm.onEvent(BackupNowEvent.Kit.FileChosen("content://again"))
        advanceUntilIdle()
        assertThat(kit.written).hasSize(1)
    }

    /** BUG30's rule on this screen: "Not now" is the moment the words are finished with. */
    @Test
    fun notNow_forgetsTheWords() = runTest(dispatcher) {
        val vm = backedUp()

        vm.onEvent(BackupNowEvent.Kit.Declined)
        vm.saveKitTo("content://kit")
        advanceUntilIdle()

        assertThat(vm.state.value.kitOffered).isFalse()
        assertThat(kit.written).isEmpty()
    }

    /** A failed write keeps them, so another place can be tried without retyping 24. */
    @Test
    fun aFailedWrite_keepsTheWordsForAnotherTry() = runTest(dispatcher) {
        val vm = backedUp()
        kit.succeeds = false

        vm.saveKitTo("content://full-disk")
        advanceUntilIdle()
        assertThat(vm.state.value.kitFailed).isTrue()
        assertThat(vm.state.value.kitOffered).isTrue()

        kit.succeeds = true
        vm.saveKitTo("content://elsewhere")
        advanceUntilIdle()
        assertThat(kit.written.single().first).isEqualTo("content://elsewhere")
        assertThat(vm.state.value.kitSaved).isTrue()
        assertThat(vm.state.value.kitFailed).isFalse()
    }

    @Test
    fun backingOutOfTheWarningOrThePicker_keepsTheOffer() = runTest(dispatcher) {
        val vm = backedUp()

        vm.onEvent(BackupNowEvent.Kit.Requested)
        vm.onEvent(BackupNowEvent.Kit.Cancelled)
        vm.saveKitTo(null)
        advanceUntilIdle()

        assertThat(vm.state.value.kitOffered).isTrue()
        assertThat(vm.state.value.kitConfirming).isFalse()
        assertThat(kit.written).isEmpty()
    }

    /** Typing again means the verified words are no longer the ones in play. */
    @Test
    fun typingNewWords_forgetsTheVerifiedOnes() = runTest(dispatcher) {
        val vm = backedUp()

        vm.onEvent(BackupNowEvent.WordCommitted("abandon"))
        vm.saveKitTo("content://kit")
        advanceUntilIdle()

        assertThat(vm.state.value.kitOffered).isFalse()
        assertThat(kit.written).isEmpty()
    }

    private companion object {
        /** The public BIP-39 test vector in a kit's payload. Nobody's key. */
        val KIT_CODE: String = PhraseQr.encode(List(23) { "abandon" } + "art")
    }
}
