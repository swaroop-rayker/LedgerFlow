package com.ledgerflow.feature.onboarding.restore

import android.content.Intent
import com.ledgerflow.core.domain.drive.ClearDriveRestoreStagingUseCase
import com.ledgerflow.core.domain.drive.ListDriveBackupsUseCase
import com.ledgerflow.core.domain.drive.StageDriveBackupUseCase
import com.ledgerflow.core.drive.DriveSignIn
import com.ledgerflow.core.drive.DriveSignInStep
import com.ledgerflow.core.testing.drive.FakeDriveAccess
import com.ledgerflow.core.testing.drive.FakeDriveFiles
import com.ledgerflow.core.testing.drive.FakeDriveRestoreStaging

/** A sign-in that answers what the test sets, and records which account was asked for. */
internal class FakeDriveSignIn(var next: DriveSignInStep = DriveSignInStep.Granted) : DriveSignIn {
    val begun = mutableListOf<String>()
    override suspend fun begin(account: String): DriveSignInStep {
        begun += account
        return next
    }

    override fun complete(data: Intent?): DriveSignInStep = next

    override suspend fun revoke(account: String?) = Unit
}

/** Restore from Drive's dependencies over the shared fakes; the screen's other tests take the defaults. */
internal fun driveActions(
    signIn: DriveSignIn = FakeDriveSignIn(),
    files: FakeDriveFiles = FakeDriveFiles(),
    staging: FakeDriveRestoreStaging = FakeDriveRestoreStaging(),
    access: FakeDriveAccess = FakeDriveAccess(),
): DriveRestoreActions = DriveRestoreActions(
    signIn = signIn,
    list = ListDriveBackupsUseCase(access, files),
    stage = StageDriveBackupUseCase(access, files, staging),
    clear = ClearDriveRestoreStagingUseCase(staging),
)
