package com.ledgerflow.feature.onboarding.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.security.SecureRandom
import javax.inject.Qualifier
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Distinguishes the word-challenge RNG from the phrase-generation RNG.
 *
 * They are separate constructor parameters so a test can make the challenge
 * deterministic without also fixing the phrase, which is what
 * `OnboardingViewModelTest` relies on to assert against known positions.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ChallengeRandom

@Module
@InstallIn(ViewModelComponent::class)
internal object OnboardingModule {

    /**
     * Backed by the injected [SecureRandom] rather than `Random.Default`.
     *
     * Predicting which three positions get asked does not by itself help an
     * attacker -- they would still need the words -- so this is not load-bearing
     * security. It is consistency: there is one CSPRNG in this app
     * (`RandomModule`), and a second, weaker source of randomness inside the
     * one screen that exists to protect the recovery phrase would be a strange
     * thing for a future reader to find and have to reason about.
     */
    @Provides
    @ChallengeRandom
    fun challengeRandom(secureRandom: SecureRandom): Random = secureRandom.asKotlinRandom()
}
