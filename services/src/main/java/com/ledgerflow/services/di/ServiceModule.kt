package com.ledgerflow.services.di

import com.ledgerflow.domain.repository.BackupRepository
import com.ledgerflow.services.backup.BackupManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindBackupRepository(
        impl: BackupManager
    ): BackupRepository
}
