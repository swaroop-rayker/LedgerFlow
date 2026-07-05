package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.MerchantPreferenceDao
import com.ledgerflow.data.db.entity.MerchantPreferenceEntity
import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MerchantPreferenceRepositoryImpl @Inject constructor(
    private val merchantPreferenceDao: MerchantPreferenceDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MerchantPreferenceRepository {

    override suspend fun saveMerchantPreference(preference: MerchantPreference): Result<Unit> = withContext(ioDispatcher) {
        try {
            merchantPreferenceDao.insertMerchantPreference(MerchantPreferenceEntity.fromDomain(preference))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getMerchantPreference(merchant: String): Result<MerchantPreference?> = withContext(ioDispatcher) {
        try {
            val entity = merchantPreferenceDao.getMerchantPreference(merchant)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun incrementUsageCount(merchant: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            merchantPreferenceDao.incrementUsageCount(merchant, System.currentTimeMillis())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
