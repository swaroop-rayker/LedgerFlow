package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.DatabaseStats
import com.ledgerflow.domain.model.Result

interface DatabaseToolsRepository {
    suspend fun getDatabaseStats(): Result<DatabaseStats>
    suspend fun clearExpenses(): Result<Unit>
    suspend fun clearPendingTransactions(): Result<Unit>
    suspend fun clearMerchantPreferences(): Result<Unit>
    suspend fun clearCategories(): Result<Unit>
    suspend fun clearSubcategories(): Result<Unit>
    suspend fun resetCategoriesToDefault(): Result<Unit>
    suspend fun factoryReset(): Result<Unit>
    suspend fun selectiveReset(tables: List<String>): Result<Unit>
    suspend fun runVacuum(): Result<Unit>
    suspend fun runIntegrityCheck(): Result<String>
    suspend fun runForeignKeyCheck(): Result<List<String>>
    suspend fun createSnapshot(): Result<Unit>
    suspend fun restoreSnapshot(): Result<Unit>
}
