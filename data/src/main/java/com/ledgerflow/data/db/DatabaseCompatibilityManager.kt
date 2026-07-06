package com.ledgerflow.data.db

import android.content.Context
import android.net.Uri
import com.ledgerflow.core.common.util.DatabaseRecoveryState
import com.ledgerflow.data.datastore.SecurityPrefsManager
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteDatabase
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject

object DatabaseCompatibilityManager {

    private const val DB_NAME = "ledgerflow_secure.db"

    fun checkCompatibility(context: Context, securityPrefsManager: SecurityPrefsManager, expectedVersion: Int): String? {
        val dbFile = context.getDatabasePath(DB_NAME)
        val fileExists = dbFile.exists()
        Timber.d("Forensic Log: Database file exists = $fileExists")
        Timber.d("Forensic Log: Expected schema version = $expectedVersion")

        if (!fileExists) {
            Timber.d("Forensic Log: Compatibility result = Compatible (Fresh database file will be created)")
            return null // fresh database, will be created normally
        }

        var db: SQLiteDatabase? = null
        try {
            SQLiteDatabase.loadLibs(context)
            val passphrase = runBlocking { securityPrefsManager.getOrCreateDatabasePassphrase() }
            
            // Try opening the database file directly using SQLCipher with raw byte passphrase
            val activeDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null
            )
            db = activeDb

            val currentVersion = activeDb.version
            Timber.d("Forensic Log: Existing schema version = $currentVersion")

            // Monotonic policy: check for downgrade
            if (currentVersion > expectedVersion) {
                val error = "Database downgrade detected: Version on disk ($currentVersion) is greater than expected version ($expectedVersion)."
                Timber.d("Forensic Log: Compatibility result = Incompatible ($error)")
                return error
            }

            // Run integrity check
            var integrityResult = "unknown"
            activeDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    integrityResult = cursor.getString(0)
                }
            }
            Timber.d("Forensic Log: Integrity check result = $integrityResult")
            if (integrityResult != "ok") {
                val error = "Database integrity check failed: $integrityResult"
                Timber.d("Forensic Log: Compatibility result = Incompatible ($error)")
                return error
            }

            // Run foreign key check
            activeDb.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                if (cursor.count > 0) {
                    val violations = StringBuilder()
                    var row = 0
                    while (cursor.moveToNext() && row < 5) {
                        violations.append("Table: ${cursor.getString(0)}, RowId: ${cursor.getLong(1)}, Parent: ${cursor.getString(2)}\n")
                        row++
                    }
                    val error = "Foreign key constraint violations detected:\n$violations"
                    Timber.d("Forensic Log: Compatibility result = Incompatible ($error)")
                    return error
                }
            }

            Timber.d("Forensic Log: Compatibility result = Compatible")
            return null // Compatible
        } catch (e: Exception) {
            val error = "Decryption or corruption failure: ${e.localizedMessage}"
            Timber.e(e, "DatabaseCompatibilityManager: Exception during compatibility verification")
            Timber.d("Forensic Log: Compatibility result = Incompatible ($error)")
            return error
        } finally {
            try {
                db?.close()
            } catch (ex: Exception) {
                Timber.e(ex, "DatabaseCompatibilityManager: Error closing database")
            }
        }
    }

    fun backupDatabaseFile(context: Context, destinationUri: Uri): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return false
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(dbFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "DatabaseCompatibilityManager: Failed to backup raw database file")
            false
        }
    }

    fun exportDatabaseToJson(context: Context, securityPrefsManager: SecurityPrefsManager, destinationUri: Uri): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return false

        var db: SQLiteDatabase? = null
        try {
            SQLiteDatabase.loadLibs(context)
            val passphrase = runBlocking { securityPrefsManager.getOrCreateDatabasePassphrase() }
            val passphraseChar = CharArray(passphrase.size) { (passphrase[it].toInt() and 0xFF).toChar() }
            
            val activeDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphraseChar,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db = activeDb

            val payload = JSONObject()
            payload.put("appVersion", "1.0.0")
            payload.put("backupVersion", 2)
            payload.put("databaseVersion", activeDb.version)
            payload.put("createdTimestamp", System.currentTimeMillis())

            // Export expenses
            val expensesArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM transactions", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val expense = JSONObject()
                        expense.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        expense.put("amount", cursor.getLong(cursor.getColumnIndexOrThrow("amount")))
                        expense.put("merchant", cursor.getString(cursor.getColumnIndexOrThrow("merchant")))
                        expense.put("category", cursor.getString(cursor.getColumnIndexOrThrow("category")))
                        expense.put("subcategory", cursor.getString(cursor.getColumnIndexOrThrow("subcategory")))
                        expense.put("paymentMethod", cursor.getString(cursor.getColumnIndexOrThrow("payment_method")))
                        expense.put("reference", cursor.getString(cursor.getColumnIndexOrThrow("reference")))
                        expense.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
                        expense.put("notes", cursor.getString(cursor.getColumnIndexOrThrow("notes")))
                        expense.put("rawMerchant", cursor.getString(cursor.getColumnIndexOrThrow("raw_merchant")))
                        expensesArray.put(expense)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read transactions table")
            }
            payload.put("expenses", expensesArray)

            // Export pending transactions
            val pendingArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM pending_transactions", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val pt = JSONObject()
                        pt.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        pt.put("amount", cursor.getLong(cursor.getColumnIndexOrThrow("amount")))
                        pt.put("merchant", cursor.getString(cursor.getColumnIndexOrThrow("merchant")))
                        pt.put("category", cursor.getString(cursor.getColumnIndexOrThrow("category")))
                        pt.put("subcategory", cursor.getString(cursor.getColumnIndexOrThrow("subcategory")))
                        pt.put("paymentMethod", cursor.getString(cursor.getColumnIndexOrThrow("payment_method")))
                        pt.put("reference", cursor.getString(cursor.getColumnIndexOrThrow("reference")))
                        pt.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
                        pt.put("notes", cursor.getString(cursor.getColumnIndexOrThrow("notes")))
                        pt.put("rawMerchant", cursor.getString(cursor.getColumnIndexOrThrow("raw_merchant")))
                        pendingArray.put(pt)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read pending transactions table")
            }
            payload.put("pendingTransactions", pendingArray)

            // Export categories
            val categoriesArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM categories", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val cat = JSONObject()
                        cat.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        cat.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        cat.put("isArchived", cursor.getInt(cursor.getColumnIndexOrThrow("is_archived")) != 0)
                        cat.put("color", cursor.getString(cursor.getColumnIndexOrThrow("color")))
                        cat.put("icon", cursor.getString(cursor.getColumnIndexOrThrow("icon")))
                        cat.put("isPinned", cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")) != 0)
                        categoriesArray.put(cat)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read categories table")
            }
            payload.put("categories", categoriesArray)

            // Export subcategories (re-map as categories with parentId)
            val subcategoriesArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM subcategories", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sub = JSONObject()
                        sub.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")) + 1000000L)
                        sub.put("parentId", cursor.getLong(cursor.getColumnIndexOrThrow("category_id")))
                        sub.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        sub.put("color", cursor.getString(cursor.getColumnIndexOrThrow("color")))
                        sub.put("icon", cursor.getString(cursor.getColumnIndexOrThrow("icon")))
                        sub.put("isArchived", cursor.getInt(cursor.getColumnIndexOrThrow("is_archived")) != 0)
                        sub.put("isPinned", cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")) != 0)
                        subcategoriesArray.put(sub)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read subcategories table")
            }
            payload.put("subcategories", subcategoriesArray)

            // Export merchant preferences
            val prefsArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM merchant_preferences", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val pref = JSONObject()
                        pref.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        pref.put("merchant", cursor.getString(cursor.getColumnIndexOrThrow("merchant")))
                        pref.put("preferredCategory", cursor.getString(cursor.getColumnIndexOrThrow("preferred_category")))
                        pref.put("preferredSubcategory", cursor.getString(cursor.getColumnIndexOrThrow("preferred_subcategory")))
                        prefsArray.put(pref)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read merchant_preferences table")
            }
            payload.put("merchantPreferences", prefsArray)

            // Export budgets
            val budgetsArray = JSONArray()
            try {
                activeDb.rawQuery("SELECT * FROM budgets", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val budget = JSONObject()
                        budget.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        budget.put("categoryId", cursor.getLong(cursor.getColumnIndexOrThrow("category_id")))
                        budget.put("amount", cursor.getLong(cursor.getColumnIndexOrThrow("amount")))
                        budget.put("period", cursor.getString(cursor.getColumnIndexOrThrow("period")))
                        budgetsArray.put(budget)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Emergency Export: Failed to read budgets table")
            }
            payload.put("budgets", budgetsArray)

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString(4))
                }
            } ?: return false

            return true
        } catch (e: Exception) {
            Timber.e(e, "DatabaseCompatibilityManager: Failed to export database to JSON")
            return false
        } finally {
            try {
                db?.close()
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }

    fun resetDatabase(context: Context): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        Timber.d("Forensic Log: Reset decision: Deleting database files at ${dbFile.absolutePath}")
        try {
            // Delete DB files
            dbFile.delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-wal").delete()
            Timber.d("DatabaseCompatibilityManager: Local database files deleted successfully.")
            return true
        } catch (e: Exception) {
            Timber.e(e, "DatabaseCompatibilityManager: Failed to delete database files")
            return false
        }
    }

    fun createSnapshot(context: Context): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return false
        val snapshotFile = File(dbFile.parentFile, "ledgerflow_secure_snapshot.db")
        return try {
            FileOutputStream(snapshotFile).use { outputStream ->
                FileInputStream(dbFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // copy WAL journals too if present
            val wal = File(dbFile.path + "-wal")
            if (wal.exists()) {
                FileOutputStream(File(snapshotFile.path + "-wal")).use { out ->
                    FileInputStream(wal).use { inp -> inp.copyTo(out) }
                }
            }
            val shm = File(dbFile.path + "-shm")
            if (shm.exists()) {
                FileOutputStream(File(snapshotFile.path + "-shm")).use { out ->
                    FileInputStream(shm).use { inp -> inp.copyTo(out) }
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "DatabaseCompatibilityManager: Failed to create database snapshot")
            false
        }
    }

    fun restoreSnapshot(context: Context): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        val snapshotFile = File(dbFile.parentFile, "ledgerflow_secure_snapshot.db")
        if (!snapshotFile.exists()) return false
        return try {
            // delete current database files
            dbFile.delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-wal").delete()

            // copy snapshot to db
            FileOutputStream(dbFile).use { outputStream ->
                FileInputStream(snapshotFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            val walSnap = File(snapshotFile.path + "-wal")
            if (walSnap.exists()) {
                FileOutputStream(File(dbFile.path + "-wal")).use { out ->
                    FileInputStream(walSnap).use { inp -> inp.copyTo(out) }
                }
            }
            val shmSnap = File(snapshotFile.path + "-shm")
            if (shmSnap.exists()) {
                FileOutputStream(File(dbFile.path + "-shm")).use { out ->
                    FileInputStream(shmSnap).use { inp -> inp.copyTo(out) }
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "DatabaseCompatibilityManager: Failed to restore database snapshot")
            false
        }
    }
}
