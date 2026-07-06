package com.ledgerflow.services.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.data.db.entity.*
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.Merchant
import com.ledgerflow.domain.model.RestoreMode
import com.ledgerflow.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: LedgerFlowDatabase,
    private val securityPrefsManager: com.ledgerflow.data.datastore.SecurityPrefsManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : com.ledgerflow.domain.repository.BackupRepository {

    companion object {
        private const val DB_FILE_NAME = "ledgerflow_secure.db"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val PORTABLE_BACKUP_VERSION = 2
        private const val CURRENT_APP_VERSION = "1.0.0"
        private const val BACKUP_FORMAT_VERSION = 1
    }

    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }

    @Serializable
    data class BackupManifest(
        val appVersion: String,
        val databaseVersion: Int,
        val schemaVersion: Int,
        val checksum: String,
        val createdTimestamp: Long,
        val expenseCount: Int,
        val pendingCount: Int,
        val encryptionVersion: String = "1.0",
        val backupFormatVersion: Int = BACKUP_FORMAT_VERSION
    )

    @Serializable
    data class PortableBackupPayload(
        val appVersion: String,
        val backupVersion: Int,
        val databaseVersion: Int,
        val createdTimestamp: Long,
        val expenses: List<com.ledgerflow.domain.model.Transaction>,
        val pendingTransactions: List<com.ledgerflow.domain.model.PendingTransaction>,
        val categories: List<com.ledgerflow.domain.model.Category>,
        val subcategories: List<com.ledgerflow.domain.model.Category>, // represented as Category with parentId
        val merchantPreferences: List<com.ledgerflow.domain.model.MerchantPreference>,
        val budgets: List<com.ledgerflow.domain.model.Budget>,
        val merchants: List<com.ledgerflow.domain.model.Merchant> = emptyList(),
        val settings: Map<String, String> = emptyMap(),
        val backupFormatVersion: Int = BACKUP_FORMAT_VERSION
    )

    override suspend fun createBackup(destinationUri: Uri, password: CharArray): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.Failure.ValidationError("Database file not found.")
            }

            // Write WAL and checkpoint changes to the main file before backup
            database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")

            val dbBytes = dbFile.readBytes()
            val checksum = calculateChecksum(dbBytes)
            val dbVersion = database.openHelper.readableDatabase.version

            // Query stats for manifest
            val db = database.openHelper.readableDatabase
            var expenseCount = 0
            var pendingCount = 0
            db.query("SELECT COUNT(*) FROM transactions").use { c ->
                if (c.moveToFirst()) expenseCount = c.getInt(0)
            }
            db.query("SELECT COUNT(*) FROM pending_transactions").use { c ->
                if (c.moveToFirst()) pendingCount = c.getInt(0)
            }

            val manifest = BackupManifest(
                appVersion = CURRENT_APP_VERSION,
                databaseVersion = dbVersion,
                schemaVersion = dbVersion,
                checksum = checksum,
                createdTimestamp = System.currentTimeMillis(),
                expenseCount = expenseCount,
                pendingCount = pendingCount
            )

            val manifestJsonBytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

            // Create ZIP containing DB and manifest
            val zipOutStream = ByteArrayOutputStream()
            ZipOutputStream(zipOutStream).use { zos ->
                // Write Manifest
                val manifestEntry = ZipEntry(MANIFEST_FILE_NAME)
                zos.putNextEntry(manifestEntry)
                zos.write(manifestJsonBytes)
                zos.closeEntry()

                // Write Database File
                val dbEntry = ZipEntry(DB_FILE_NAME)
                zos.putNextEntry(dbEntry)
                zos.write(dbBytes)
                zos.closeEntry()
            }

            val zipBytes = zipOutStream.toByteArray()
            val encryptedBytes = BackupEngine.encrypt(zipBytes, password)

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                output.write(encryptedBytes)
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open destination stream."))

            // Log Audit Entry
            database.auditLogDao().insertAuditLog(
                AuditLogEntity(
                    operation = "FULL_BACKUP_EXPORT",
                    details = "Exported encrypted database backup. Expenses: $expenseCount, Pending: $pendingCount"
                )
            )

            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BackupManager: Error creating full backup.")
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun restoreBackup(sourceUri: Uri, password: CharArray): Result<Unit> = withContext(ioDispatcher) {
        try {
            val encryptedBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.readBytes()
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open source stream."))

            val zipBytes = try {
                BackupEngine.decrypt(encryptedBytes, password)
            } catch (e: Exception) {
                return@withContext Result.Failure.ValidationError("Incorrect password or corrupt backup file.")
            }

            var manifest: BackupManifest? = null
            var dbBytes: ByteArray? = null

            // Read ZIP contents
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        MANIFEST_FILE_NAME -> {
                            val out = ByteArrayOutputStream()
                            zis.copyTo(out)
                            manifest = json.decodeFromString(BackupManifest.serializer(), out.toString(Charsets.UTF_8.name()))
                        }
                        DB_FILE_NAME -> {
                            val out = ByteArrayOutputStream()
                            zis.copyTo(out)
                            dbBytes = out.toByteArray()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (manifest == null || dbBytes == null) {
                return@withContext Result.Failure.ValidationError("Invalid backup structure: missing manifest or database payload.")
            }

            // Verify checksum and corruption
            val computedChecksum = calculateChecksum(dbBytes!!)
            if (computedChecksum != manifest!!.checksum) {
                return@withContext Result.Failure.ValidationError("Backup payload is corrupt. Checksum validation failed.")
            }

            // Verify versions
            val currentDbVersion = database.openHelper.readableDatabase.version
            if (manifest!!.databaseVersion > currentDbVersion) {
                return@withContext Result.Failure.ValidationError("Cannot restore backup from newer DB schema version ${manifest!!.databaseVersion} (Current: $currentDbVersion)")
            }

            // Close current database to allow file replacement
            database.close()

            // Write restored SQLite db file
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            val parentDir = dbFile.parentFile ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Parent directory not found."))

            // Safe database replace
            dbFile.delete()
            File(parentDir, "$DB_FILE_NAME-shm").delete()
            File(parentDir, "$DB_FILE_NAME-wal").delete()

            FileOutputStream(dbFile).use { fos ->
                fos.write(dbBytes!!)
            }

            // Re-open and verify database integrity
            val validationDb = database.openHelper.writableDatabase
            validationDb.execSQL("PRAGMA integrity_check")

            // Log Audit Entry in newly restored database
            database.auditLogDao().insertAuditLog(
                AuditLogEntity(
                    operation = "FULL_BACKUP_RESTORE",
                    details = "Restored database from encrypted backup. Manifest created: ${manifest!!.createdTimestamp}"
                )
            )

            // Mark database seeded after restore to prevent startup seeding
            securityPrefsManager.saveDatabaseSeeded(true)

            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BackupManager: Error restoring full backup.")
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun createPortableBackup(destinationUri: Uri): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dbVersion = database.openHelper.readableDatabase.version

            // Query all domain data using Flows
            val expenses = database.transactionDao().getTransactionsFlow(0, Long.MAX_VALUE).first().map { it.toDomain() }
            val pending = database.pendingTransactionDao().getPendingTransactionsFlow().first().map { it.toDomain() }
            val categories = database.categoryDao().getCategoriesFlow().first().map { it.toDomain() }
            
            // Map subcategory entities to domain Categories (with parentId set)
            val subcategories = database.subcategoryDao().getSubcategoriesFlow().first().map {
                Category(
                    id = it.id + 1000000L, // Offset to avoid ID conflicts with parents
                    name = it.name,
                    parentId = it.categoryId,
                    color = it.color,
                    icon = it.icon,
                    isArchived = it.isArchived,
                    isPinned = it.isPinned
                )
            }
            val preferences = database.merchantPreferenceDao().getMerchantPreferencesFlow().first().map { it.toDomain() }
            val budgets = database.budgetDao().getBudgetsFlow().first().map { it.toDomain() }
            
            // Query merchants with relations
            val merchants = database.merchantDao().getMerchantsWithRelations().map { it.toDomain() }

            val payload = PortableBackupPayload(
                appVersion = CURRENT_APP_VERSION,
                backupVersion = PORTABLE_BACKUP_VERSION,
                databaseVersion = dbVersion,
                createdTimestamp = System.currentTimeMillis(),
                expenses = expenses,
                pendingTransactions = pending,
                categories = categories,
                subcategories = subcategories,
                merchantPreferences = preferences,
                budgets = budgets,
                merchants = merchants,
                settings = mapOf(
                    "backup_format_version" to BACKUP_FORMAT_VERSION.toString(),
                    "app_version" to CURRENT_APP_VERSION
                )
            )

            val payloadJson = json.encodeToString(PortableBackupPayload.serializer(), payload)
            
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                output.write(payloadJson.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open destination stream."))

            // Log Audit Entry
            database.auditLogDao().insertAuditLog(
                AuditLogEntity(
                    operation = "PORTABLE_BACKUP_EXPORT",
                    details = "Exported portable JSON backup. Expenses: ${expenses.size}, Merchants: ${merchants.size}"
                )
            )

            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BackupManager: Error exporting portable backup.")
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun restorePortableBackup(sourceUri: Uri, mode: RestoreMode): Result<Unit> = withContext(ioDispatcher) {
        val payloadJson = try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open source stream."))
        } catch (e: Exception) {
            return@withContext Result.Failure.ValidationError("Failed to read JSON payload: ${e.localizedMessage}")
        }

        val payload = try {
            json.decodeFromString(PortableBackupPayload.serializer(), payloadJson)
        } catch (e: Exception) {
            return@withContext Result.Failure.ValidationError("Failed to parse portable backup JSON. Check file structure.")
        }

        try {
            database.withTransaction {
                if (mode == RestoreMode.REPLACE_EVERYTHING) {
                    val sdb = database.openHelper.writableDatabase
                    sdb.execSQL("DELETE FROM attachments")
                    sdb.execSQL("DELETE FROM transactions")
                    sdb.execSQL("DELETE FROM pending_transactions")
                    sdb.execSQL("DELETE FROM budgets")
                    sdb.execSQL("DELETE FROM subcategories")
                    sdb.execSQL("DELETE FROM categories")
                    sdb.execSQL("DELETE FROM merchant_preferences")
                    sdb.execSQL("DELETE FROM merchant_aliases")
                    sdb.execSQL("DELETE FROM merchant_regexes")
                    sdb.execSQL("DELETE FROM merchants")
                }

                // 1. Restore/Merge Categories
                val existingCats = database.categoryDao().getCategoriesFlow().first()
                val catIdMap = mutableMapOf<Long, Long>()

                payload.categories.forEach { cat ->
                    val matching = existingCats.firstOrNull { it.name.equals(cat.name, ignoreCase = true) }
                    if (matching != null) {
                        catIdMap[cat.id] = matching.id
                        if (mode == RestoreMode.MERGE) {
                            database.categoryDao().insertCategory(
                                CategoryEntity(
                                    id = matching.id,
                                    name = cat.name,
                                    isArchived = cat.isArchived,
                                    color = cat.color,
                                    icon = cat.icon,
                                    isPinned = cat.isPinned
                                )
                            )
                        }
                    } else {
                        val newId = database.categoryDao().insertCategory(
                            CategoryEntity(
                                name = cat.name,
                                isArchived = cat.isArchived,
                                color = cat.color,
                                icon = cat.icon,
                                isPinned = cat.isPinned
                            )
                        )
                        catIdMap[cat.id] = newId
                    }
                }

                // 2. Restore/Merge Subcategories
                val existingSubcats = database.subcategoryDao().getSubcategoriesFlow().first()
                payload.subcategories.forEach { sub ->
                    val targetParentId = catIdMap[sub.parentId] ?: return@forEach
                    val matchingSub = existingSubcats.firstOrNull {
                        it.categoryId == targetParentId && it.name.equals(sub.name, ignoreCase = true)
                    }
                    if (matchingSub != null) {
                        if (mode == RestoreMode.MERGE) {
                            database.subcategoryDao().insertSubcategory(
                                SubcategoryEntity(
                                    id = matchingSub.id,
                                    categoryId = targetParentId,
                                    name = sub.name,
                                    color = sub.color,
                                    icon = sub.icon,
                                    isArchived = sub.isArchived,
                                    isPinned = sub.isPinned
                                )
                            )
                        }
                    } else {
                        database.subcategoryDao().insertSubcategory(
                            SubcategoryEntity(
                                categoryId = targetParentId,
                                name = sub.name,
                                color = sub.color,
                                icon = sub.icon,
                                isArchived = sub.isArchived,
                                isPinned = sub.isPinned
                            )
                        )
                    }
                }

                // 3. Restore Merchants (Normalized with Aliases and Regexes)
                val existingMerchants = database.merchantDao().getMerchantsWithRelations()
                payload.merchants.forEach { m ->
                    val matching = existingMerchants.firstOrNull {
                        it.merchant.normalizedName.equals(m.normalizedName, ignoreCase = true) ||
                        it.merchant.displayName.equals(m.displayName, ignoreCase = true)
                    }

                    if (matching != null) {
                        if (mode == RestoreMode.MERGE) {
                            // Update Merchant Scalar Fields
                            database.merchantDao().insertMerchant(
                                MerchantEntity.fromDomain(m).copy(id = matching.merchant.id)
                            )
                            // Insert missing Aliases
                            m.aliases.forEach { alias ->
                                if (matching.aliases.none { it.alias.equals(alias, ignoreCase = true) }) {
                                    database.merchantAliasDao().insertAlias(
                                        MerchantAliasEntity(merchantId = matching.merchant.id, alias = alias)
                                    )
                                }
                            }
                            // Insert missing Regexes
                            m.regexPatterns.forEach { regex ->
                                if (matching.regexes.none { it.pattern.equals(regex, ignoreCase = true) }) {
                                    database.merchantRegexDao().insertRegex(
                                        MerchantRegexEntity(merchantId = matching.merchant.id, pattern = regex)
                                    )
                                }
                            }
                        }
                    } else {
                        // Insert fresh merchant
                        val newMerchantId = database.merchantDao().insertMerchant(MerchantEntity.fromDomain(m).copy(id = 0))
                        m.aliases.forEach { alias ->
                            database.merchantAliasDao().insertAlias(
                                MerchantAliasEntity(merchantId = newMerchantId, alias = alias)
                            )
                        }
                        m.regexPatterns.forEach { regex ->
                            database.merchantRegexDao().insertRegex(
                                MerchantRegexEntity(merchantId = newMerchantId, pattern = regex)
                            )
                        }
                    }
                }

                // 4. Restore/Merge Expenses (Duplicate Detection: same timestamp, amount, and merchant)
                val existingTxns = database.transactionDao().getTransactionsFlow(0, Long.MAX_VALUE).first()
                payload.expenses.forEach { tx ->
                    val matching = existingTxns.firstOrNull {
                        it.timestamp == tx.timestamp && it.amount == tx.amount && it.merchant.equals(tx.merchant, ignoreCase = true)
                    }
                    if (matching != null) {
                        if (mode == RestoreMode.MERGE) {
                            database.transactionDao().insertTransaction(
                                TransactionEntity.fromDomain(tx).copy(id = matching.id)
                            )
                        }
                    } else {
                        database.transactionDao().insertTransaction(TransactionEntity.fromDomain(tx).copy(id = 0))
                    }
                }

                // 5. Restore/Merge Pending Transactions
                val existingPending = database.pendingTransactionDao().getPendingTransactionsFlow().first()
                payload.pendingTransactions.forEach { pt ->
                    val matching = existingPending.firstOrNull {
                        it.timestamp == pt.timestamp && it.amount == pt.amount && it.merchant.equals(pt.merchant, ignoreCase = true)
                    }
                    if (matching != null) {
                        if (mode == RestoreMode.MERGE) {
                            database.pendingTransactionDao().insertPendingTransaction(
                                PendingTransactionEntity.fromDomain(pt).copy(id = matching.id)
                            )
                        }
                    } else {
                        database.pendingTransactionDao().insertPendingTransaction(PendingTransactionEntity.fromDomain(pt).copy(id = 0))
                    }
                }

                // 6. Restore/Merge Merchant Preferences
                val existingPrefs = database.merchantPreferenceDao().getMerchantPreferencesFlow().first()
                payload.merchantPreferences.forEach { pref ->
                    val matching = existingPrefs.firstOrNull { it.merchant.equals(pref.merchant, ignoreCase = true) }
                    if (matching != null) {
                        if (mode == RestoreMode.MERGE) {
                            database.merchantPreferenceDao().insertMerchantPreference(
                                MerchantPreferenceEntity.fromDomain(pref).copy(id = matching.id)
                            )
                        }
                    } else {
                        database.merchantPreferenceDao().insertMerchantPreference(
                            MerchantPreferenceEntity.fromDomain(pref).copy(id = 0)
                        )
                    }
                }

                // 7. Restore/Merge Budgets
                val existingBudgets = database.budgetDao().getBudgetsFlow().first()
                payload.budgets.forEach { budget ->
                    val targetCatId = catIdMap[budget.categoryId] ?: return@forEach
                    val matching = existingBudgets.firstOrNull {
                        it.categoryId == targetCatId && it.period == budget.period.name && it.amount == budget.amount
                    }
                    if (matching != null) {
                        if (mode == RestoreMode.MERGE) {
                            database.budgetDao().insertBudget(
                                BudgetEntity.fromDomain(budget).copy(id = matching.id, categoryId = targetCatId)
                            )
                        }
                    } else {
                        database.budgetDao().insertBudget(
                            BudgetEntity.fromDomain(budget).copy(id = 0, categoryId = targetCatId)
                        )
                    }
                }

                // Log Audit Entry
                database.auditLogDao().insertAuditLog(
                    AuditLogEntity(
                        operation = "PORTABLE_RESTORE",
                        details = "Imported portable JSON backup in $mode mode. Payload format version: ${payload.backupFormatVersion}"
                    )
                )
            }
            // Mark database seeded after restore to prevent startup seeding
            securityPrefsManager.saveDatabaseSeeded(true)
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "BackupManager: Error importing portable backup.")
            Result.Failure.DatabaseError(e)
        }
    }

    private fun calculateChecksum(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
