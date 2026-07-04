package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.PaymentMethod
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface PaymentMethodRepository {
    suspend fun savePaymentMethod(paymentMethod: PaymentMethod): Result<Unit>
    suspend fun getPaymentMethodById(paymentMethodId: Long): Result<PaymentMethod?>
    fun getPaymentMethodsFlow(): Flow<List<PaymentMethod>>
}
