package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.PaymentMethodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentMethodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentMethod(paymentMethod: PaymentMethodEntity)

    @Query("SELECT * FROM payment_methods WHERE id = :paymentMethodId")
    suspend fun getPaymentMethodById(paymentMethodId: Long): PaymentMethodEntity?

    @Query("SELECT * FROM payment_methods ORDER BY name ASC")
    fun getPaymentMethodsFlow(): Flow<List<PaymentMethodEntity>>
}
