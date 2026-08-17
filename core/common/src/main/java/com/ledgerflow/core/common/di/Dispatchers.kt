package com.ledgerflow.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Disk, database and crypto work. Never the main thread (CLAUDE.md §5). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class IoDispatcher

/** CPU-bound work: parsing, sorting, aggregation. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class DefaultDispatcher

/**
 * Dispatchers are injected, never referenced directly (CLAUDE.md §5).
 *
 * The reason is testability rather than taste: a class that reaches for
 * `Dispatchers.IO` internally cannot be driven by `runTest`'s scheduler, so its
 * tests either sleep or race. Every suspending seam in this app -- the unlock
 * flow, the draft debounce, the backup writer -- has a test that needs to
 * control time.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DispatchersModule {

    @Provides
    @IoDispatcher
    public fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    public fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
