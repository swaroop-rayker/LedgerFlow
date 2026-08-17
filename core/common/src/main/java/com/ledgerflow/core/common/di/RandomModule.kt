package com.ledgerflow.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

/**
 * The application's single source of randomness.
 *
 * Provided rather than constructed at each call site for one reason that
 * matters more than convention: every consumer of randomness in this app is
 * security-relevant -- BIP-39 entropy, GCM nonces, UUIDv7's 74 random bits,
 * the word-challenge positions. A binding makes "which RNG is this?" a question
 * with one answer, and makes a `java.util.Random` slipping into one of those
 * paths a visible change rather than an invisible one.
 *
 * `@Singleton` because [SecureRandom] is thread-safe and seeding it is the
 * expensive part; sharing one instance avoids paying that on every injection.
 */
@Module
@InstallIn(SingletonComponent::class)
public object RandomModule {

    @Provides
    @Singleton
    public fun secureRandom(): SecureRandom = SecureRandom()
}
