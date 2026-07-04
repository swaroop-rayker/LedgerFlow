package com.ledgerflow.services.backup

import android.content.Context
import android.net.Uri
import com.ledgerflow.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : com.ledgerflow.domain.repository.BackupRepository {

    companion object {
        private const val DB_FILE_NAME = "ledgerflow_secure.db"
    }

    override suspend fun createBackup(destinationUri: Uri, password: CharArray): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.Failure.ValidationError("Database file not found.")
            }

            // Create ZIP of DB in memory
            val tempZipFile = File.createTempFile("ledgerflow_backup", ".zip", context.cacheDir)
            ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
                val entry = ZipEntry(DB_FILE_NAME)
                zos.putNextEntry(entry)
                FileInputStream(dbFile).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }

            // Encrypt ZIP file
            val zipBytes = tempZipFile.readBytes()
            tempZipFile.delete()
            
            val encryptedBytes = BackupEngine.encrypt(zipBytes, password)

            // Write to destination using Storage Access Framework (SAF)
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                output.write(encryptedBytes)
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open destination stream."))

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun restoreBackup(sourceUri: Uri, password: CharArray): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Read encrypted payload
            val encryptedBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.readBytes()
            } ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Failed to open source stream."))

            // Decrypt payload
            val zipBytes = try {
                BackupEngine.decrypt(encryptedBytes, password)
            } catch (e: Exception) {
                return@withContext Result.Failure.ValidationError("Incorrect password or corrupt backup file.")
            }

            // Extract ZIP content
            val tempZipFile = File.createTempFile("ledgerflow_restore", ".zip", context.cacheDir)
            tempZipFile.writeBytes(zipBytes)

            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            val parentDir = dbFile.parentFile ?: return@withContext Result.Failure.DatabaseError(NullPointerException("Parent directory not found."))
            
            ZipInputStream(FileInputStream(tempZipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == DB_FILE_NAME) {
                        // Delete existing files first to prevent Room file locking issues
                        dbFile.delete()
                        File(parentDir, "$DB_FILE_NAME-shm").delete()
                        File(parentDir, "$DB_FILE_NAME-wal").delete()

                        FileOutputStream(dbFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            tempZipFile.delete()

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
