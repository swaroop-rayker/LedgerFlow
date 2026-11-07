package com.ledgerflow.feature.onboarding.restore

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.backup.PreviewRestoreUseCase
import com.ledgerflow.core.domain.backup.ReplaceVaultFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.drive.LocalBackupFile
import com.ledgerflow.core.drive.DriveSignInStep
import com.ledgerflow.core.testing.backup.FakeRestoreRepository
import com.ledgerflow.core.testing.drive.FakeDriveFiles
import com.ledgerflow.core.testing.drive.FakeDriveRestoreStaging
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
 * Restore from Google Drive on the restore screen (ADR-0034 §7; owner's
 * placement A, download on pick): the account first, then Drive's backups in
 * the same list, a tap that downloads the backup and its receipts into
 * staging, and the existing restore reading that staged copy. Leaving, or
 * choosing a folder instead, empties the staging folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreFromDriveTest {

    private val dispatcher = StandardTestDispatcher()
    private val validator = FakeRecoveryPhraseValidator()
    private val repository = FakeRestoreRepository(
        outcome = RestoreOutcome.Done(
            rows = 3,
            imagesRestored = 1,
            imagesNotFound = 0,
            imagesUnreadable = 0,
            imagesFailed = 0,
        ),
    )
    private val signIn = FakeDriveSignIn()
    private val files = FakeDriveFiles().apply {
        backup(OLDER, byteArrayOf(1))
        backup(NEWEST, byteArrayOf(2))
        receipt("r1.lfba", byteArrayOf(3))
    }
    private val staging = FakeDriveRestoreStaging()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RestoreViewModel(
        RestoreFromBackupUseCase(validator, repository),
        PreviewRestoreUseCase(validator, repository),
        ReplaceVaultFromBackupUseCase(validator, repository),
        repository,
        validator,
        driveActions(signIn = signIn, files = files, staging = staging),
    )

    private fun RestoreViewModel.signInAs(account: String) {
        onEvent(RestoreEvent.Drive.Requested)
        onEvent(RestoreEvent.Drive.AccountPickerLaunched)
        onEvent(RestoreEvent.Drive.AccountChosen(account))
    }

    @Test
    fun fromDrive_asksForTheAccount_thenListsItsBackupsNewestFirst() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(RestoreEvent.Drive.Requested)
        assertThat(vm.state.value.drive?.choosingAccount).isTrue()

        vm.onEvent(RestoreEvent.Drive.AccountPickerLaunched)
        vm.onEvent(RestoreEvent.Drive.AccountChosen("me@example.com"))
        advanceUntilIdle()

        assertThat(signIn.begun).containsExactly("me@example.com")
        val listing = requireNotNull(vm.state.value.drive?.listing)
        assertThat(listing.backups.map { it.name }).containsExactly(NEWEST, OLDER).inOrder()
        assertThat(listing.receipts.map { it.name }).containsExactly("r1.lfba")
        // Nothing downloads until a backup is picked, so nothing can be restored yet.
        assertThat(staging.files).isEmpty()
        assertThat(vm.state.value.source).isNull()
    }

    /** A tap downloads the backup with its receipts; the restore then reads the staged copy. */
    @Test
    fun pickingABackup_downloadsItWithItsReceipts_andTheRestoreReadsIt() = runTest(dispatcher) {
        val vm = viewModel()
        vm.signInAs("me@example.com")
        advanceUntilIdle()

        vm.onEvent(RestoreEvent.Drive.BackupPicked(NEWEST))
        advanceUntilIdle()

        assertThat(staging.files.keys).containsExactly(
            LocalBackupFile.Backup(NEWEST),
            LocalBackupFile.Attachment("r1.lfba"),
        )
        assertThat(vm.state.value.drive?.staged).isEqualTo(NEWEST)
        assertThat(vm.state.value.source).isEqualTo(RestoreSource.Staged(NEWEST))

        repeat(validator.wordCount) { vm.onEvent(RestoreEvent.WordCommitted("abandon")) }
        vm.onEvent(RestoreEvent.Submitted)
        advanceUntilIdle()

        assertThat(repository.restoreCalls.single().first).isEqualTo(RestoreSource.Staged(NEWEST))
        assertThat(vm.state.value.isDone).isTrue()
    }

    @Test
    fun anEmptyDrive_saysSo_andOffersNothingToRestore() = runTest(dispatcher) {
        val vm = RestoreViewModel(
            RestoreFromBackupUseCase(validator, repository),
            PreviewRestoreUseCase(validator, repository),
            ReplaceVaultFromBackupUseCase(validator, repository),
            repository,
            validator,
            driveActions(signIn = signIn, files = FakeDriveFiles(), staging = staging),
        )
        vm.signInAs("me@example.com")
        advanceUntilIdle()

        assertThat(vm.state.value.drive?.problem).isEqualTo("No LedgerFlow backups in this account's Google Drive.")
        assertThat(vm.state.value.source).isNull()
    }

    @Test
    fun aDeclinedSignIn_listsNothing() = runTest(dispatcher) {
        signIn.next = DriveSignInStep.Declined
        val vm = viewModel()

        vm.signInAs("me@example.com")
        advanceUntilIdle()

        assertThat(vm.state.value.drive?.listing).isNull()
        assertThat(vm.state.value.drive?.problem).contains("wasn't allowed")
    }

    /** Choosing a folder instead, or leaving, leaves nothing downloaded behind. */
    @Test
    fun aFolderInstead_orLeaving_emptiesTheStagingFolder() = runTest(dispatcher) {
        val vm = viewModel()
        vm.signInAs("me@example.com")
        advanceUntilIdle()
        vm.onEvent(RestoreEvent.Drive.BackupPicked(NEWEST))
        advanceUntilIdle()
        assertThat(staging.files).isNotEmpty()

        vm.onEvent(RestoreEvent.FolderChosen("content://tree/backups"))
        advanceUntilIdle()

        assertThat(vm.state.value.drive).isNull()
        assertThat(staging.files).isEmpty()

        vm.signInAs("me@example.com")
        advanceUntilIdle()
        vm.onEvent(RestoreEvent.Drive.BackupPicked(OLDER))
        advanceUntilIdle()
        vm.onEvent(RestoreEvent.Left)
        advanceUntilIdle()

        assertThat(staging.files).isEmpty()
    }

    @Test
    fun dismissingTheAccountPicker_putsTheScreenBack() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(RestoreEvent.Drive.Requested)
        vm.onEvent(RestoreEvent.Drive.AccountChosen(null))
        advanceUntilIdle()

        assertThat(vm.state.value.drive).isNull()
        assertThat(signIn.begun).isEmpty()
    }

    private companion object {
        const val NEWEST = "ledgerflow-20261107-030200.lfbk"
        const val OLDER = "ledgerflow-20261106-030100.lfbk"
    }
}
