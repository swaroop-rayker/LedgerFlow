package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.MerchantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchant(merchant: MerchantEntity)

    @Query("SELECT * FROM merchants WHERE id = :merchantId")
    suspend fun getMerchantById(merchantId: Long): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun getMerchantByNormalizedName(normalizedName: String): MerchantEntity?

    @Query("SELECT * FROM merchants ORDER BY display_name ASC")
    fun getMerchantsFlow(): Flow<List<MerchantEntity>>

    @Query("UPDATE merchants SET is_archived = 1 WHERE id = :merchantId")
    suspend fun archiveMerchant(merchantId: Long)
}
