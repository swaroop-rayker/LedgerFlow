package com.ledgerflow.data.repository

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.data.db.DatabaseCompatibilityManager
import com.ledgerflow.data.db.DatabaseSeedingHelper
import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.domain.model.DatabaseStats
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.DatabaseToolsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseToolsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: LedgerFlowDatabase,
    private val securityPrefsManager: SecurityPrefsManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DatabaseToolsRepository {

    override suspend fun getDatabaseStats(): Result<DatabaseStats> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath("ledgerflow_secure.db")
            val path = dbFile.absolutePath
            val sizeBytes = if (dbFile.exists()) dbFile.length() else 0L
            val schemaVersion = database.openHelper.readableDatabase.version
            val roomVersion = "2.6.1"

            val db = database.openHelper.readableDatabase
            
            var expenseCount = 0
            var pendingCount = 0
            var categoryCount = 0
            var subcategoryCount = 0
            var preferenceCount = 0
            var merchantCount = 0
            var budgetCount = 0
            var auditLogCount = 0
            var sqliteVersion = "Unknown"
            var walMode = false
            var pageCount = 0L
            var integrityStatus = "Unknown"
            var foreignKeyStatus = "Unknown"

            db.query("SELECT COUNT(*) FROM transactions").use { c ->
                if (c.moveToFirst()) expenseCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM pending_transactions").use { c ->
                if (c.moveToFirst()) pendingCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM categories").use { c ->
                if (c.moveToFirst()) categoryCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM subcategories").use { c ->
                if (c.moveToFirst()) subcategoryCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM merchant_preferences").use { c ->
                if (c.moveToFirst()) preferenceCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM merchants").use { c ->
                if (c.moveToFirst()) merchantCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM budgets").use { c ->
                if (c.moveToFirst()) budgetCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM audit_logs").use { c ->
                if (c.moveToFirst()) auditLogCount = c.getInt(0)
            }
            db.query("SELECT sqlite_version()").use { c ->
                if (c.moveToFirst()) sqliteVersion = c.getString(0)
            }
            db.query("PRAGMA journal_mode").use { c ->
                if (c.moveToFirst()) walMode = c.getString(0).equals("wal", ignoreCase = true)
            }
            db.query("PRAGMA page_count").use { c ->
                if (c.moveToFirst()) pageCount = c.getLong(0)
            }

            integrityStatus = runIntegrityCheck(db)
            val fkViolations = runForeignKeyCheck(db)
            foreignKeyStatus = if (fkViolations.isEmpty()) "ok" else "${fkViolations.size} violations"

            // Get last timestamps from audit logs
            var lastBackup = 0L
            var lastRestore = 0L
            var lastVacuum = 0L
            try {
                db.query("SELECT timestamp FROM audit_logs WHERE operation = 'FULL_BACKUP_EXPORT' OR operation = 'PORTABLE_BACKUP_EXPORT' ORDER BY timestamp DESC LIMIT 1").use { c ->
                    if (c.moveToFirst()) lastBackup = c.getLong(0)
                }
                db.query("SELECT timestamp FROM audit_logs WHERE operation = 'FULL_BACKUP_RESTORE' OR operation = 'PORTABLE_RESTORE' ORDER BY timestamp DESC LIMIT 1").use { c ->
                    if (c.moveToFirst()) lastRestore = c.getLong(0)
                }
                db.query("SELECT timestamp FROM audit_logs WHERE operation = 'VACUUM' ORDER BY timestamp DESC LIMIT 1").use { c ->
                    if (c.moveToFirst()) lastVacuum = c.getLong(0)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reading audit logs for timestamps")
            }

            val created = securityPrefsManager.getDatabaseCreatedTimestamp()
            val lastOpened = securityPrefsManager.getDatabaseLastOpenedTimestamp()

            Result.Success(
                DatabaseStats(
                    path = path,
                    sizeBytes = sizeBytes,
                    schemaVersion = schemaVersion,
                    roomVersion = roomVersion,
                    appVersion = "1.0.0",
                    backupFormatVersion = 1,
                    sqliteVersion = sqliteVersion,
                    encryptionStatus = "Encrypted (SQLCipher)",
                    integrityStatus = integrityStatus,
                    foreignKeyStatus = foreignKeyStatus,
                    walMode = walMode,
                    pageCount = pageCount,
                    lastVacuum = lastVacuum,
                    lastBackup = lastBackup,
                    lastRestore = lastRestore,
                    expenseCount = expenseCount,
                    pendingTransactionCount = pendingCount,
                    categoryCount = categoryCount,
                    subcategoryCount = subcategoryCount,
                    merchantPreferenceCount = preferenceCount,
                    merchantCount = merchantCount,
                    budgetCount = budgetCount,
                    auditLogCount = auditLogCount,
                    createdTimestamp = created,
                    lastOpenedTimestamp = lastOpened
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "DatabaseToolsRepository: Error reading database stats.")
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun clearExpenses(): Result<Unit> {
        return runResetTransaction("Clear Expenses") { db ->
            db.execSQL("DELETE FROM attachments")
            db.execSQL("DELETE FROM transactions")
        }
    }

    override suspend fun clearPendingTransactions(): Result<Unit> {
        return runResetTransaction("Clear Pending Transactions") { db ->
            db.execSQL("DELETE FROM pending_transactions")
        }
    }

    override suspend fun clearMerchantPreferences(): Result<Unit> {
        return runResetTransaction("Clear Merchant Preferences") { db ->
            db.execSQL("DELETE FROM merchant_preferences")
        }
    }

    override suspend fun clearCategories(): Result<Unit> {
        return runResetTransaction("Clear Categories") { db ->
            db.execSQL("DELETE FROM subcategories")
            db.execSQL("DELETE FROM categories")
        }
    }

    override suspend fun clearSubcategories(): Result<Unit> {
        return runResetTransaction("Clear Subcategories") { db ->
            db.execSQL("DELETE FROM subcategories")
        }
    }

    override suspend fun resetCategoriesToDefault(): Result<Unit> {
        return runResetTransaction("Reset Categories to Default") { db ->
            db.execSQL("DELETE FROM subcategories")
            db.execSQL("DELETE FROM categories")
            DatabaseSeedingHelper.seedDatabase(db)
        }
    }

    override suspend fun factoryReset(): Result<Unit> {
        return runResetTransaction("Factory Reset Database") { db ->
            // Delete all user data in dependency order
            db.execSQL("DELETE FROM attachments")
            db.execSQL("DELETE FROM transactions")
            db.execSQL("DELETE FROM budgets")
            db.execSQL("DELETE FROM subcategories")
            db.execSQL("DELETE FROM merchants")
            db.execSQL("DELETE FROM payment_methods")
            db.execSQL("DELETE FROM categories")
            db.execSQL("DELETE FROM pending_transactions")
            db.execSQL("DELETE FROM merchant_preferences")
            db.execSQL("DELETE FROM recurring_rules")

            // Re-seed only defaults
            DatabaseSeedingHelper.seedDatabase(db)
        }
    }

    override suspend fun selectiveReset(tables: List<String>): Result<Unit> {
        return runResetTransaction("Selective Reset: ${tables.joinToString(", ")}") { db ->
            // Delete selected tables in safe dependency order
            if (tables.contains("attachments") || tables.contains("transactions")) {
                db.execSQL("DELETE FROM attachments")
            }
            if (tables.contains("transactions")) {
                db.execSQL("DELETE FROM transactions")
            }
            if (tables.contains("pending_transactions")) {
                db.execSQL("DELETE FROM pending_transactions")
            }
            if (tables.contains("merchant_preferences")) {
                db.execSQL("DELETE FROM merchant_preferences")
            }
            if (tables.contains("budgets")) {
                db.execSQL("DELETE FROM budgets")
            }
            if (tables.contains("subcategories") || tables.contains("categories")) {
                db.execSQL("DELETE FROM subcategories")
            }
            if (tables.contains("categories")) {
                db.execSQL("DELETE FROM categories")
            }
            if (tables.contains("recurring_rules") || tables.contains("analytics")) {
                db.execSQL("DELETE FROM recurring_rules")
            }
        }
    }

    override suspend fun runVacuum(): Result<Unit> = withContext(ioDispatcher) {
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM")
            database.auditLogDao().insertAuditLog(
                com.ledgerflow.data.db.entity.AuditLogEntity(
                    operation = "VACUUM",
                    details = "Ran explicit database VACUUM command"
                )
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun runIntegrityCheck(): Result<String> = withContext(ioDispatcher) {
        try {
            val db = database.openHelper.readableDatabase
            val integrity = runIntegrityCheck(db)
            Result.Success(integrity)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun runForeignKeyCheck(): Result<List<String>> = withContext(ioDispatcher) {
        try {
            val db = database.openHelper.readableDatabase
            val violations = runForeignKeyCheck(db)
            Result.Success(violations)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun createSnapshot(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val success = DatabaseCompatibilityManager.createSnapshot(context)
            if (success) {
                database.auditLogDao().insertAuditLog(
                    com.ledgerflow.data.db.entity.AuditLogEntity(
                        operation = "SNAPSHOT_CREATE",
                        details = "Created manual database snapshot"
                    )
                )
                Result.Success(Unit)
            } else {
                Result.Failure.DatabaseError(Exception("Failed to write snapshot file"))
            }
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun restoreSnapshot(): Result<Unit> = withContext(ioDispatcher) {
        try {
            database.close()
            val success = DatabaseCompatibilityManager.restoreSnapshot(context)
            if (success) {
                // Force open db helper to restore connection
                database.openHelper.writableDatabase.execSQL("PRAGMA integrity_check")
                database.auditLogDao().insertAuditLog(
                    com.ledgerflow.data.db.entity.AuditLogEntity(
                        operation = "SNAPSHOT_RESTORE",
                        details = "Restored database from manual snapshot"
                    )
                )
                Result.Success(Unit)
            } else {
                Result.Failure.DatabaseError(Exception("Failed to restore snapshot: snapshot file does not exist"))
            }
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    private suspend fun runResetTransaction(
        tag: String,
        action: (SupportSQLiteDatabase) -> Unit
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        Timber.d("DatabaseToolsRepository: Reset requested - %s", tag)
        val db = database.openHelper.writableDatabase

        try {
            db.beginTransaction()
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            action(db)
            db.setTransactionSuccessful()
            db.endTransaction()

            // Run integrity and foreign key checks
            val integrity = runIntegrityCheck(db)
            val foreignKeys = runForeignKeyCheck(db)

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d(
                "DatabaseToolsRepository: %s finished in %d ms. Integrity: %s, FK check: %s",
                tag, elapsed, integrity, foreignKeys
            )

            if (integrity != "ok") {
                return@withContext Result.Failure.DatabaseError(
                    Exception("Database integrity check failed: $integrity")
                )
            }
            if (foreignKeys.isNotEmpty()) {
                return@withContext Result.Failure.DatabaseError(
                    Exception("Database foreign key check failed:\n${foreignKeys.joinToString("\n")}")
                )
            }

            // Log Audit Entry
            database.auditLogDao().insertAuditLog(
                com.ledgerflow.data.db.entity.AuditLogEntity(
                    operation = "DATABASE_RESET_OR_CLEAR",
                    details = "Wiped or cleared tables via: $tag"
                )
            )

            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "DatabaseToolsRepository: Failed to execute reset transaction for %s", tag)
            if (db.inTransaction()) {
                try {
                    db.endTransaction()
                } catch (txEx: Exception) {
                    Timber.e(txEx, "Error ending transaction during rollback")
                }
            }
            Result.Failure.DatabaseError(e)
        }
    }

    private fun runIntegrityCheck(db: SupportSQLiteDatabase): String {
        return try {
            db.query("PRAGMA integrity_check").use { c ->
                if (c.moveToFirst()) c.getString(0) else "failed"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun runForeignKeyCheck(db: SupportSQLiteDatabase): List<String> {
        val violations = mutableListOf<String>()
        try {
            db.query("PRAGMA foreign_key_check").use { c ->
                if (c.moveToFirst()) {
                    do {
                        val table = c.getString(0)
                        val rowId = c.getLong(1)
                        val parentTable = c.getString(2)
                        val fkid = c.getInt(3)
                        violations.add("FK violation: Table $table at row $rowId references parent $parentTable (index $fkid)")
                    } while (c.moveToNext())
                }
            }
        } catch (e: Exception) {
            violations.add("Error running FK check: ${e.message}")
        }
        return violations
    }
}
