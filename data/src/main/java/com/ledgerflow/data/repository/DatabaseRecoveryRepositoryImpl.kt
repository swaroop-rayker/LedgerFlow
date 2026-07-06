package com.ledgerflow.data.repository

import android.content.Context
import android.net.Uri
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.data.db.DatabaseCompatibilityManager
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.DatabaseRecoveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRecoveryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securityPrefsManager: SecurityPrefsManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DatabaseRecoveryRepository {

    override suspend fun backupDatabaseFile(destinationUri: Uri): Result<Unit> = withContext(ioDispatcher) {
        val success = DatabaseCompatibilityManager.backupDatabaseFile(context, destinationUri)
        if (success) Result.Success(Unit) else Result.Failure.DatabaseError(Exception("Backup failed"))
    }

    override suspend fun exportDatabaseToJson(destinationUri: Uri): Result<Unit> = withContext(ioDispatcher) {
        val success = DatabaseCompatibilityManager.exportDatabaseToJson(context, securityPrefsManager, destinationUri)
        if (success) Result.Success(Unit) else Result.Failure.DatabaseError(Exception("JSON export failed"))
    }

    override suspend fun resetDatabase(): Result<Unit> = withContext(ioDispatcher) {
        val success = DatabaseCompatibilityManager.resetDatabase(context)
        if (success) Result.Success(Unit) else Result.Failure.DatabaseError(Exception("Reset failed"))
    }
}
