package com.ledgerflow.data.repository

import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.DataIntegrityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DataIntegrityRepositoryImpl @Inject constructor(
    private val database: LedgerFlowDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DataIntegrityRepository {

    override suspend fun runDatabaseIntegrityCheck(): Result<List<String>> = withContext(ioDispatcher) {
        try {
            val cursor = database.openHelper.writableDatabase.query("PRAGMA integrity_check")
            val results = mutableListOf<String>()
            if (cursor.moveToFirst()) {
                do {
                    val message = cursor.getString(0)
                    if (message != "ok") {
                        results.add(message)
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()
            Result.Success(results)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun runForeignKeyCheck(): Result<List<String>> = withContext(ioDispatcher) {
        try {
            val cursor = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check")
            val results = mutableListOf<String>()
            if (cursor.moveToFirst()) {
                do {
                    val table = cursor.getString(0)
                    val rowId = cursor.getLong(1)
                    val parentTable = cursor.getString(2)
                    val fkid = cursor.getInt(3)
                    results.add("FK violation: Table $table at row $rowId references parent $parentTable (index $fkid)")
                } while (cursor.moveToNext())
            }
            cursor.close()
            Result.Success(results)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
