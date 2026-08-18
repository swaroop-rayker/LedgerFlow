package com.ledgerflow.core.data.di

import com.ledgerflow.core.database.LedgerFlowDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/**
 * The vault's database file name.
 *
 * A qualifier rather than a constant read inside `VaultSession`, so that
 * instrumented tests can point a session at their own file. They used to share
 * the real one and delete it in teardown, so every `connectedAndroidTest` run
 * wiped the debug install's ledger -- which CLAUDE.md §8 BUG1(e) explicitly
 * forbids, and which destroyed a real vault twice before anyone noticed the
 * tests were the cause.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class VaultDatabaseName

@Module
@InstallIn(SingletonComponent::class)
public object VaultDatabaseModule {

    @Provides
    @VaultDatabaseName
    public fun databaseName(): String = LedgerFlowDatabase.DATABASE_NAME
}
