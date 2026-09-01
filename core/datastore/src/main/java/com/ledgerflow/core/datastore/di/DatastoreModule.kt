package com.ledgerflow.core.datastore.di

import com.ledgerflow.core.datastore.ListenerHealthDataStore
import com.ledgerflow.core.datastore.NotificationSetupDataStore
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.NotificationSetupStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The out-of-vault stores, bound (ADR-0020).
 *
 * The module is expected to stay small. `:core:datastore` is scoped by ADR-0020
 * to operational metadata about the app's own machinery — if this file grows a
 * third and fourth binding, check each against that scope rather than adding it
 * because the module happens to exist.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DatastoreModule {

    @Binds
    internal abstract fun listenerHealthStore(store: ListenerHealthDataStore): ListenerHealthStore

    @Binds
    internal abstract fun notificationSetupStore(
        store: NotificationSetupDataStore,
    ): NotificationSetupStore
}
