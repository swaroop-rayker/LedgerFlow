package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.MerchantRegexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantRegexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegex(regex: MerchantRegexEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegexes(regexes: List<MerchantRegexEntity>)

    @Query("DELETE FROM merchant_regexes WHERE id = :id")
    suspend fun deleteRegex(id: Long)

    @Query("SELECT * FROM merchant_regexes WHERE merchant_id = :merchantId")
    suspend fun getRegexesForMerchant(merchantId: Long): List<MerchantRegexEntity>

    @Query("SELECT * FROM merchant_regexes WHERE merchant_id = :merchantId")
    fun getRegexesForMerchantFlow(merchantId: Long): Flow<List<MerchantRegexEntity>>

    @Query("SELECT * FROM merchant_regexes")
    suspend fun getAllRegexes(): List<MerchantRegexEntity>

    @Query("SELECT * FROM merchant_regexes")
    fun getAllRegexesFlow(): Flow<List<MerchantRegexEntity>>

    @Query("DELETE FROM merchant_regexes WHERE merchant_id = :merchantId")
    suspend fun deleteRegexesForMerchant(merchantId: Long)
}
