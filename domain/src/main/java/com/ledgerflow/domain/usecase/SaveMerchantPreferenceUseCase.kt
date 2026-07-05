package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import javax.inject.Inject

class SaveMerchantPreferenceUseCase @Inject constructor(
    private val repository: MerchantPreferenceRepository
) {
    suspend operator fun invoke(preference: MerchantPreference): Result<Unit> = repository.saveMerchantPreference(preference)
}
