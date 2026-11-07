package com.ledgerflow.feature.settings.drive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The Drive page's copy queue: WorkManager in the app, a recorder in tests. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DriveSettingsModule {
    @Binds
    fun driveCopyQueue(impl: WorkManagerDriveCopyQueue): DriveCopyQueue
}
