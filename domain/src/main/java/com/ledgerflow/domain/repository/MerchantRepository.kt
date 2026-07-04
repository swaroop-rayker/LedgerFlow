package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Merchant
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface MerchantRepository {
    suspend fun saveMerchant(merchant: Merchant): Result<Unit>
    suspend fun getMerchantById(merchantId: Long): Result<Merchant?>
    suspend fun getMerchantByNormalizedName(normalizedName: String): Result<Merchant?>
    fun getMerchantsFlow(): Flow<List<Merchant>>
    suspend fun archiveMerchant(merchantId: Long): Result<Unit>
}
