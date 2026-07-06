package com.ledgerflow.domain.repository

import android.net.Uri
import com.ledgerflow.domain.model.Result

interface DatabaseRecoveryRepository {
    suspend fun backupDatabaseFile(destinationUri: Uri): Result<Unit>
    suspend fun exportDatabaseToJson(destinationUri: Uri): Result<Unit>
    suspend fun resetDatabase(): Result<Unit>
}
