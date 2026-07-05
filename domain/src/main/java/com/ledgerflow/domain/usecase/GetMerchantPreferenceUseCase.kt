package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import javax.inject.Inject

class GetMerchantPreferenceUseCase @Inject constructor(
    private val repository: MerchantPreferenceRepository
) {
    suspend operator fun invoke(merchant: String): Result<MerchantPreference?> = repository.getMerchantPreference(merchant)
}
