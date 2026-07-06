package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.MerchantAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: MerchantAliasEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAliases(aliases: List<MerchantAliasEntity>)

    @Query("DELETE FROM merchant_aliases WHERE id = :id")
    suspend fun deleteAlias(id: Long)

    @Query("SELECT * FROM merchant_aliases WHERE merchant_id = :merchantId")
    suspend fun getAliasesForMerchant(merchantId: Long): List<MerchantAliasEntity>

    @Query("SELECT * FROM merchant_aliases WHERE merchant_id = :merchantId")
    fun getAliasesForMerchantFlow(merchantId: Long): Flow<List<MerchantAliasEntity>>

    @Query("SELECT * FROM merchant_aliases")
    suspend fun getAllAliases(): List<MerchantAliasEntity>

    @Query("SELECT * FROM merchant_aliases")
    fun getAllAliasesFlow(): Flow<List<MerchantAliasEntity>>

    @Query("DELETE FROM merchant_aliases WHERE merchant_id = :merchantId")
    suspend fun deleteAliasesForMerchant(merchantId: Long)
}
