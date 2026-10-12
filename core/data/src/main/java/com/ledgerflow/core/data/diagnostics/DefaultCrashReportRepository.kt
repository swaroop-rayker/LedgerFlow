package com.ledgerflow.core.data.diagnostics

import android.content.Context
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.diagnostics.CrashReportRepository
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * [CrashReportRepository] over [CrashReportFiles] (BUG7(c)).
 *
 * The reports were written by a process that has since died, so this one only
 * reads them: once, lazily, the first time Home asks, and again after a save
 * or a dismiss changes the folder.
 */
@Singleton
public class DefaultCrashReportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CrashReportRepository {

    private val files = CrashReportFiles(File(context.filesDir, CrashReportFiles.DIRECTORY))

    private val state = MutableStateFlow<CrashSummary?>(null)
    @Volatile private var read = false

    // The first read lists a folder, so it happens on `io`: Home collects this
    // on the main thread, where StrictMode (debug) kills a disk read.
    override val summary: Flow<CrashSummary?> = flow {
        if (!read) {
            state.value = withContext(io) { current() }
            read = true
        }
        emitAll(state)
    }

    override suspend fun exportTo(destinationUri: String): Boolean = withContext(io) {
        runCatching {
            // "wt" truncates: the picker can hand back an existing file.
            val stream = context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")
                ?: return@runCatching false
            stream.bufferedWriter(Charsets.UTF_8).use { out ->
                files.reports().forEachIndexed { index, report ->
                    if (index > 0) out.write("\n----------------------------------------\n\n")
                    out.write(report.readText(Charsets.UTF_8))
                }
            }
            true
        }.getOrDefault(false)
    }

    override fun suggestedFileName(): String {
        val date = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        return "LedgerFlow-crash-reports-$date.txt"
    }

    override suspend fun dismissAll(): Unit = withContext(io) {
        files.deleteAll()
        state.value = null
    }

    private fun current(): CrashSummary? {
        val reports = files.reports()
        val latest = files.latestAt() ?: return null
        return CrashSummary(count = reports.size, latestAt = latest)
    }
}
