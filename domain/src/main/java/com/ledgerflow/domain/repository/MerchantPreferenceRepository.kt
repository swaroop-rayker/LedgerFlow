package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result

interface MerchantPreferenceRepository {
    suspend fun saveMerchantPreference(preference: MerchantPreference): Result<Unit>
    suspend fun getMerchantPreference(merchant: String): Result<MerchantPreference?>
    suspend fun incrementUsageCount(merchant: String): Result<Unit>
}
