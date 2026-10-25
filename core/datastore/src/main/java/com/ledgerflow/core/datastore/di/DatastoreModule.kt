package com.ledgerflow.core.datastore.di

import com.ledgerflow.core.datastore.AppearanceDataStore
import com.ledgerflow.core.datastore.CaptureGapDataStore
import com.ledgerflow.core.datastore.ListenerHealthDataStore
import com.ledgerflow.core.datastore.NotificationSetupDataStore
import com.ledgerflow.core.datastore.RegionDataStore
import com.ledgerflow.core.domain.appearance.AppearanceStore
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.NotificationSetupStore
import com.ledgerflow.core.domain.region.RegionStore
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

    /** §16 Q16's window; within ADR-0020's rule (see the ADR's key table). */
    @Binds
    internal abstract fun captureGapStore(store: CaptureGapDataStore): CaptureGapStore

    @Binds
    internal abstract fun notificationSetupStore(
        store: NotificationSetupDataStore,
    ): NotificationSetupStore

    /**
     * Settings > Appearance. Not ADR-0020's operational metadata: the user's
     * own display choices, admitted by ADR-0030 on its narrower test.
     */
    @Binds
    internal abstract fun appearanceStore(store: AppearanceDataStore): AppearanceStore

    /** Settings > Currency & region, on the same ADR-0030 test. */
    @Binds
    internal abstract fun regionStore(store: RegionDataStore): RegionStore
}
