package com.ledgerflow.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgerflow.data.datastore.SecurityPrefsManager
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object DatabaseSeedManager {

    fun seedIfNecessary(db: SupportSQLiteDatabase, securityPrefsManager: SecurityPrefsManager) {
        val alreadySeeded = runBlocking {
            securityPrefsManager.isDatabaseSeeded()
        }
        Timber.d("Forensic Log: Seed decision: alreadySeeded = $alreadySeeded")

        if (alreadySeeded) {
            Timber.d("DatabaseSeedManager: Database already seeded according to preferences. Skipping.")
            return
        }

        // Verify if categories are empty on disk
        var categoryCount = 0
        try {
            db.query("SELECT COUNT(*) FROM categories").use { cursor ->
                if (cursor.moveToFirst()) {
                    categoryCount = cursor.getInt(0)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "DatabaseSeedManager: Failed to query categories count")
        }
        Timber.d("Forensic Log: Seed decision: categoryCount = $categoryCount")

        if (categoryCount > 0) {
            Timber.d("DatabaseSeedManager: Database tables contain custom user categories. Skipping seeding to prevent overwrites.")
            runBlocking {
                securityPrefsManager.saveDatabaseSeeded(true)
            }
            return
        }

        Timber.d("DatabaseSeedManager: Fresh empty database detected. Seeding defaults.")
        try {
            DatabaseSeedingHelper.seedDatabase(db)
            runBlocking {
                securityPrefsManager.saveDatabaseSeeded(true)
            }
            Timber.d("DatabaseSeedManager: Seeding completed successfully.")
        } catch (e: Exception) {
            Timber.e(e, "DatabaseSeedManager: Critical error during seeding")
        }
    }
}
