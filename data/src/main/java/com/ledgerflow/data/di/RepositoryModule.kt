package com.ledgerflow.data.di

import com.ledgerflow.data.repository.BudgetRepositoryImpl
import com.ledgerflow.data.repository.CategoryRepositoryImpl
import com.ledgerflow.data.repository.MerchantRepositoryImpl
import com.ledgerflow.data.repository.TransactionRepositoryImpl
import com.ledgerflow.domain.repository.BudgetRepository
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.MerchantRepository
import com.ledgerflow.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(
        impl: TransactionRepositoryImpl
    ): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(
        impl: CategoryRepositoryImpl
    ): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindMerchantRepository(
        impl: MerchantRepositoryImpl
    ): MerchantRepository

    @Binds
    @Singleton
    abstract fun bindBudgetRepository(
        impl: BudgetRepositoryImpl
    ): BudgetRepository

    @Binds
    @Singleton
    abstract fun bindDataIntegrityRepository(
        impl: com.ledgerflow.data.repository.DataIntegrityRepositoryImpl
    ): com.ledgerflow.domain.repository.DataIntegrityRepository

    @Binds
    @Singleton
    abstract fun bindPaymentMethodRepository(
        impl: com.ledgerflow.data.repository.PaymentMethodRepositoryImpl
    ): com.ledgerflow.domain.repository.PaymentMethodRepository

    @Binds
    @Singleton
    abstract fun bindPendingTransactionRepository(
        impl: com.ledgerflow.data.repository.PendingTransactionRepositoryImpl
    ): com.ledgerflow.domain.repository.PendingTransactionRepository

    @Binds
    @Singleton
    abstract fun bindMerchantPreferenceRepository(
        impl: com.ledgerflow.data.repository.MerchantPreferenceRepositoryImpl
    ): com.ledgerflow.domain.repository.MerchantPreferenceRepository
}
