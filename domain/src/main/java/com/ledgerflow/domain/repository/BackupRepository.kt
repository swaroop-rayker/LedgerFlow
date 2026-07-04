package com.ledgerflow.domain.repository

import android.net.Uri
import com.ledgerflow.domain.model.Result

interface BackupRepository {
    suspend fun createBackup(destinationUri: Uri, password: CharArray): Result<Unit>
    suspend fun restoreBackup(sourceUri: Uri, password: CharArray): Result<Unit>
}
