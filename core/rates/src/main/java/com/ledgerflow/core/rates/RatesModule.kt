package com.ledgerflow.core.rates

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The rates port, bound here so nothing outside `:core:rates` implements it (ADR-0036 §4). */
@Module
@InstallIn(SingletonComponent::class)
internal interface RatesModule {

    @Binds
    fun ratesSource(impl: EcbRatesSource): RatesSource
}
