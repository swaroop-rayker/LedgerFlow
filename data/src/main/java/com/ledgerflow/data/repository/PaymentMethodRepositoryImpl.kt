package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.PaymentMethodDao
import com.ledgerflow.data.db.entity.PaymentMethodEntity
import com.ledgerflow.domain.model.PaymentMethod
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.PaymentMethodRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PaymentMethodRepositoryImpl @Inject constructor(
    private val paymentMethodDao: PaymentMethodDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PaymentMethodRepository {

    override suspend fun savePaymentMethod(paymentMethod: PaymentMethod): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (paymentMethod.name.isBlank()) {
                return@withContext Result.Failure.ValidationError("Payment method name cannot be blank.")
            }
            paymentMethodDao.insertPaymentMethod(PaymentMethodEntity.fromDomain(paymentMethod))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getPaymentMethodById(paymentMethodId: Long): Result<PaymentMethod?> = withContext(ioDispatcher) {
        try {
            val entity = paymentMethodDao.getPaymentMethodById(paymentMethodId)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getPaymentMethodsFlow(): Flow<List<PaymentMethod>> {
        return paymentMethodDao.getPaymentMethodsFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
