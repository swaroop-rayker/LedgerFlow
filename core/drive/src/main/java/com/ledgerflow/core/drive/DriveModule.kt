package com.ledgerflow.core.drive

import com.ledgerflow.core.domain.drive.DriveAccess
import com.ledgerflow.core.domain.drive.DriveFiles
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The two network ports, bound here so nothing outside `:core:drive` implements them (ADR-0034). */
@Module
@InstallIn(SingletonComponent::class)
internal interface DriveModule {

    @Binds
    fun driveAccess(impl: PlayServicesDriveAccess): DriveAccess

    @Binds
    fun driveFiles(impl: HttpDriveFiles): DriveFiles

    @Binds
    fun driveSignIn(impl: PlayServicesDriveSignIn): DriveSignIn
}
