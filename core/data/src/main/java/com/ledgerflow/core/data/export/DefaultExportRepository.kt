package com.ledgerflow.core.data.export

import android.content.Context
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.export.ExportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Zipped per-table CSV, written to a SAF destination (SPEC.md §5.9, ADR-0017).
 *
 * **Streamed into the document, not staged in a temp file.** That is the
 * opposite of what `DatabaseBackupManager` does, and the difference is
 * deliberate: a `.lfbk` is written temp -> fsync -> verify -> rename because a
 * truncated backup sitting where a good one used to be turns a backup system
 * into a data-loss mechanism (BUG4). An export has no such hazard. It is a
 * *copy* leaving the app; a half-written one costs the user a retry and nothing
 * else, and the database it came from is untouched either way. Staging it would
 * mean writing the user's complete unencrypted financial history into
 * `filesDir` as a side effect of exporting it, which is a worse property than
 * the one it would buy.
 *
 * Everything runs on [io]. StrictMode is armed with `penaltyDeath` in debug and
 * re-throws any main-thread disk touch inside `com.ledgerflow`, so a stream
 * opened on `Dispatchers.Main.immediate` -- which is what `viewModelScope` uses
 * -- kills the app outright.
 */
@Singleton
public class DefaultExportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ExportRepository {

    override suspend fun exportCsv(destinationUri: String): ExportResult = withContext(io) {
        // `whenUnlocked().first()` rather than `requireDatabase()`: the latter
        // throws, and a locked vault here is a state to report rather than a
        // crash. The flow is derived from a StateFlow, so `first()` returns the
        // current value without suspending on anything.
        val database = session.whenUnlocked().first()
            ?: return@withContext ExportResult.VaultLocked

        runCatching {
            val payload = DatabaseBackupManager(database).export()
            val documents = CsvTables.documents(payload)

            // "wt" truncates. SAF hands back the existing document when the user
            // picks a name that is already there, and without truncation a
            // smaller export leaves the tail of the previous one behind -- which
            // in a zip means a file that opens and is subtly wrong.
            val stream = context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")
                ?: return@runCatching ExportResult.Failure("The chosen location could not be opened")

            stream.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    documents.forEach { document ->
                        zip.putNextEntry(ZipEntry(document.fileName))
                        zip.write(document.render().toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }

            ExportResult.Success(
                fileCount = documents.size,
                rowCount = documents.sumOf { it.rows.size },
            )
        }.getOrElse { error ->
            // Typed refusal rather than a throw: this is a repository boundary
            // (CLAUDE.md §5), and every failure here is a storage failure the
            // user can only respond to by picking somewhere else.
            ExportResult.Failure(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    /**
     * Dated, and deliberately not timed.
     *
     * Two exports on one day collide, and SAF's own picker resolves that by
     * appending `(1)` rather than silently overwriting -- which is the better
     * behaviour to inherit than a filename so precise that a user's folder fills
     * with near-identical names they cannot tell apart.
     */
    override fun suggestedFileName(): String {
        val today = FILE_DATE.format(Instant.ofEpochMilli(clock.nowMillis()))
        return "LedgerFlow-export-$today.zip"
    }

    private companion object {
        /**
         * Device zone, unlike the ISO columns inside the files.
         *
         * A filename is read by the person who made it, on the day they made
         * it, and dating it in UTC would put yesterday's date on an evening
         * export in Asia/Kolkata. The columns inside are UTC because those are
         * parsed later, possibly elsewhere; the two are answering different
         * questions.
         */
        private val FILE_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneId.systemDefault())
    }
}
