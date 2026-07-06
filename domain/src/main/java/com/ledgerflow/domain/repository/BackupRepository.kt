package com.ledgerflow.domain.repository

import android.net.Uri
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.RestoreMode

interface BackupRepository {
    suspend fun createBackup(destinationUri: Uri, password: CharArray): Result<Unit>
    suspend fun restoreBackup(sourceUri: Uri, password: CharArray): Result<Unit>
    suspend fun createPortableBackup(destinationUri: Uri): Result<Unit>
    suspend fun restorePortableBackup(sourceUri: Uri, mode: RestoreMode = RestoreMode.MERGE): Result<Unit>
}
