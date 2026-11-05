package com.ledgerflow.feature.onboarding.restore

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.backup.PreviewRestoreUseCase
import com.ledgerflow.core.domain.backup.ReplaceVaultFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.PutBackOutcome
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.vault.PhraseQr
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

    private fun viewModel() = RestoreViewModel(
        RestoreFromBackupUseCase(validator, repository),
        PreviewRestoreUseCase(validator, repository),
        ReplaceVaultFromBackupUseCase(validator, repository),
        repository,
        validator,
    )

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

    // ─── Scanning a Recovery Kit (ADR-0028) ─────────────────────────────────

    @Test
    fun scanningAKit_fillsTheWordsAndClosesTheScanner() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RestoreEvent.Scanner.Requested)

        vm.onEvent(RestoreEvent.Scanner.Read(KIT_CODE))

        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
        assertThat(vm.state.value.isScanning).isFalse()
    }

    @Test
    fun scanningSomeoneElsesCode_keepsTheCameraOpen() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RestoreEvent.Scanner.Requested)

        vm.onEvent(RestoreEvent.Scanner.Read("WIFI:S:cafe;T:WPA;P:latte;;"))

        assertThat(vm.state.value.isScanning).isTrue()
        assertThat(vm.state.value.entry.words).isEmpty()
    }

    /** Leaving forgets a scanned phrase exactly as it forgets a typed one (BUG30). */
    @Test
    fun leavingAfterAScan_forgetsTheWords() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RestoreEvent.Scanner.Read(KIT_CODE))

        vm.onEvent(RestoreEvent.Left)

        assertThat(vm.state.value.entry.words).isEmpty()
    }

    // ── From Settings, over this phone's ledger (ADR-0033) ──────────────────

    private fun replacing(): RestoreViewModel = viewModel().apply { setMode(RestoreMode.Replace) }

    private suspend fun RestoreViewModel.toConfirmation(scope: kotlinx.coroutines.test.TestScope) {
        chooseFolder()
        scope.advanceUntilIdle()
        typeFullPhrase()
        onEvent(RestoreEvent.Submitted)
        scope.advanceUntilIdle()
    }

    /** The words open the backup first; nothing is replaced by the first tap. */
    @Test
    fun fromSettings_continueOpensTheBackup_andShowsTheConfirmation_withoutReplacing() = runTest(dispatcher) {
        val vm = replacing()

        vm.toConfirmation(this)

        assertThat(repository.previewCalls.single().first)
            .isEqualTo(RestoreSource.InFolder("content://tree/backups", newest))
        assertThat(repository.replaceCalls).isEmpty()
        assertThat(repository.restoreCalls).isEmpty()
        assertThat(vm.state.value.isConfirming).isTrue()
        assertThat(vm.state.value.preview).isEqualTo(repository.previewResult)
        assertWithMessage("the confirmation only reads the words")
            .that(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    /** §7.3: Replace waits for DELETE. A keyboard's capital and stray spaces are forgiven; nothing else is. */
    @Test
    fun fromSettings_replaceWaitsForDeleteTyped() = runTest(dispatcher) {
        val vm = replacing()
        vm.toConfirmation(this)

        listOf("", "DELET", "DELETED", "remove").forEach { typed ->
            vm.onEvent(RestoreEvent.ConfirmationChanged(typed))
            vm.onEvent(RestoreEvent.Replaced)
            advanceUntilIdle()
            assertWithMessage("\"$typed\"").that(vm.state.value.canReplace).isFalse()
        }
        assertThat(repository.replaceCalls).isEmpty()

        listOf("DELETE", "Delete", " delete ").forEach { typed ->
            vm.onEvent(RestoreEvent.ConfirmationChanged(typed))
            assertWithMessage("\"$typed\"").that(vm.state.value.canReplace).isTrue()
        }
    }

    @Test
    fun fromSettings_replaceHandsOverWhatWasChosen_andForgetsTheWords() = runTest(dispatcher) {
        val vm = replacing()
        vm.toConfirmation(this)
        vm.onEvent(RestoreEvent.ConfirmationChanged("DELETE"))

        vm.onEvent(RestoreEvent.Replaced)
        advanceUntilIdle()

        val (source, words) = repository.replaceCalls.single()
        assertThat(source).isEqualTo(RestoreSource.InFolder("content://tree/backups", newest))
        assertThat(words).hasSize(validator.wordCount)
        assertThat(vm.state.value.result).isEqualTo(done)
        assertThat(vm.state.value.isDone).isTrue()
        assertThat(vm.state.value.isConfirming).isFalse()
        assertThat(vm.state.value.entry.words).isEmpty()
    }

    /** A backup that does not open never reaches the confirmation, and says why. */
    @Test
    fun fromSettings_aBackupThatDoesNotOpen_saysWhy_andAsksForNothing() = runTest(dispatcher) {
        repository.previewResult = RestorePreview.Refused(RestoreOutcome.WrongPhrase)
        val vm = replacing()

        vm.toConfirmation(this)

        assertThat(vm.state.value.isConfirming).isFalse()
        assertThat(vm.state.value.result).isEqualTo(RestoreOutcome.WrongPhrase)
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    /** A rollback returns to the words with its sentence: the phone is as it was. */
    @Test
    fun fromSettings_aRolledBackReplace_returnsToTheWords_andSaysNothingChanged() = runTest(dispatcher) {
        repository.outcome = RestoreOutcome.RolledBack
        val vm = replacing()
        vm.toConfirmation(this)
        vm.onEvent(RestoreEvent.ConfirmationChanged("DELETE"))

        vm.onEvent(RestoreEvent.Replaced)
        advanceUntilIdle()

        assertThat(vm.state.value.isConfirming).isFalse()
        assertThat(vm.state.value.result).isEqualTo(RestoreOutcome.RolledBack)
        assertThat(RestoreOutcome.RolledBack.message()).contains("as it was")
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    @Test
    fun fromSettings_backFromTheConfirmation_keepsTheWords_andForgetsDelete() = runTest(dispatcher) {
        val vm = replacing()
        vm.toConfirmation(this)
        vm.onEvent(RestoreEvent.ConfirmationChanged("DELETE"))

        vm.onEvent(RestoreEvent.ConfirmationLeft)

        assertThat(vm.state.value.isConfirming).isFalse()
        assertThat(vm.state.value.confirmation).isEmpty()
        assertThat(vm.state.value.entry.words).hasSize(validator.wordCount)
    }

    /** Leaving forgets the words (BUG30) and keeps which restore this is. */
    @Test
    fun fromSettings_leaving_forgetsTheWords_andStaysAReplace() = runTest(dispatcher) {
        val vm = replacing()
        vm.toConfirmation(this)

        vm.onEvent(RestoreEvent.Left)

        assertThat(vm.state.value.entry.words).isEmpty()
        assertThat(vm.state.value.preview).isNull()
        assertThat(vm.state.value.mode).isEqualTo(RestoreMode.Replace)
    }

    /** At first run the words still restore directly: there is nothing to replace. */
    @Test
    fun atFirstRun_submitRestores_andOpensNoConfirmation() = runTest(dispatcher) {
        val vm = viewModel()
        vm.chooseFolder()
        advanceUntilIdle()
        vm.typeFullPhrase()

        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.previewCalls).isEmpty()
        assertThat(repository.restoreCalls).hasSize(1)
    }

    @Test
    fun resuming_offersToPutBack_onlyWhenSomethingWasSetAside() = runTest(dispatcher) {
        val nothingAside = viewModel().apply { setMode(RestoreMode.Resuming) }
        advanceUntilIdle()
        assertThat(nothingAside.state.value.canPutBack).isFalse()
        nothingAside.onEvent(RestoreEvent.PutBack)
        advanceUntilIdle()
        assertThat(repository.putBackCalls).isEqualTo(0)

        repository.setAside = true
        val vm = viewModel().apply { setMode(RestoreMode.Resuming) }
        advanceUntilIdle()
        assertThat(vm.state.value.canPutBack).isTrue()

        vm.onEvent(RestoreEvent.PutBack)
        advanceUntilIdle()

        assertThat(repository.putBackCalls).isEqualTo(1)
        assertThat(vm.state.value.putBackFailed).isFalse()
    }

    @Test
    fun resuming_aPutBackThatFails_saysSo() = runTest(dispatcher) {
        repository.setAside = true
        repository.putBackResult = PutBackOutcome.Failed
        val vm = viewModel().apply { setMode(RestoreMode.Resuming) }
        advanceUntilIdle()

        vm.onEvent(RestoreEvent.PutBack)
        advanceUntilIdle()

        assertThat(vm.state.value.putBackFailed).isTrue()
        assertThat(vm.state.value.isWorking).isFalse()
    }

    private companion object {
        /** The public BIP-39 test vector in a kit's payload. Nobody's key. */
        val KIT_CODE: String = PhraseQr.encode(List(23) { "abandon" } + "art")
    }
}
