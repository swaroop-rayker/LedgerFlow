package com.ledgerflow.core.common.di

import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

/** The cross-cutting primitives every layer above needs. */
@Module
@InstallIn(SingletonComponent::class)
public object CoreCommonModule {

    /**
     * One generator, shared.
     *
     * UUIDv7's time-sortable prefix is the whole reason it was chosen over v4
     * (§6.0) -- it keeps index locality on insert. Sharing the instance keeps
     * the monotonicity guarantee the generator's own test asserts, which a
     * per-injection instance would only hold by coincidence.
     */
    @Provides
    @Singleton
    public fun uuid7Generator(random: SecureRandom): Uuid7Generator = Uuid7Generator(random)

    @Provides
    @Singleton
    public fun clock(): Clock = Clock.System
}
