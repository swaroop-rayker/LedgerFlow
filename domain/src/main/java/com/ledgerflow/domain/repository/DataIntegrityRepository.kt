package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Result

interface DataIntegrityRepository {
    suspend fun runDatabaseIntegrityCheck(): Result<List<String>>
    suspend fun runForeignKeyCheck(): Result<List<String>>
}
