package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.MerchantDao
import com.ledgerflow.data.db.entity.MerchantEntity
import com.ledgerflow.domain.model.Merchant
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MerchantRepositoryImpl @Inject constructor(
    private val merchantDao: MerchantDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MerchantRepository {

    override suspend fun saveMerchant(merchant: Merchant): Result<Unit> = withContext(ioDispatcher) {
        try {
            merchantDao.insertMerchant(MerchantEntity.fromDomain(merchant))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getMerchantById(merchantId: Long): Result<Merchant?> = withContext(ioDispatcher) {
        try {
            val entity = merchantDao.getMerchantById(merchantId)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getMerchantByNormalizedName(normalizedName: String): Result<Merchant?> = withContext(ioDispatcher) {
        try {
            val entity = merchantDao.getMerchantByNormalizedName(normalizedName)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getMerchantsFlow(): Flow<List<Merchant>> {
        return merchantDao.getMerchantsFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun archiveMerchant(merchantId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            merchantDao.archiveMerchant(merchantId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
