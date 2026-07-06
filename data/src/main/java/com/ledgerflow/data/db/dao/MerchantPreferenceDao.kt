package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.MerchantPreferenceEntity

@Dao
interface MerchantPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchantPreference(preference: MerchantPreferenceEntity)

    @Query("SELECT * FROM merchant_preferences WHERE LOWER(merchant) = LOWER(:merchant) LIMIT 1")
    suspend fun getMerchantPreference(merchant: String): MerchantPreferenceEntity?

    @Query("UPDATE merchant_preferences SET usage_count = usage_count + 1, last_used = :timestamp WHERE LOWER(merchant) = LOWER(:merchant)")
    suspend fun incrementUsageCount(merchant: String, timestamp: Long)

    @Query("SELECT * FROM merchant_preferences ORDER BY merchant ASC")
    fun getMerchantPreferencesFlow(): kotlinx.coroutines.flow.Flow<List<MerchantPreferenceEntity>>
}
