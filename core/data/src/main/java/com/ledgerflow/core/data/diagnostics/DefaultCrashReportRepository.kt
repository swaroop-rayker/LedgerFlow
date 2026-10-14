package com.ledgerflow.core.data.diagnostics

import android.content.Context
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.diagnostics.CrashReportRepository
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
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
 *
 * **Constructing it touches no disk (BUG51).** Hilt builds it on the main
 * thread while Home composes, and resolving `context.filesDir` there is a disk
 * read: in debug, StrictMode's penaltyDeath killed the app every time Home
 * opened. The folder is resolved on first use, which is always inside
 * `withContext(io)`. Its two Android touchpoints are functions so a JVM test
 * can prove both.
 */
@Singleton
public class DefaultCrashReportRepository internal constructor(
    directory: () -> File,
    private val openOutput: (destinationUri: String) -> OutputStream?,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : CrashReportRepository {

    @Inject public constructor(
        @ApplicationContext context: Context,
        clock: Clock,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        directory = { File(context.filesDir, CrashReportFiles.DIRECTORY) },
        // "wt" truncates: the picker can hand back an existing file.
        openOutput = { uri -> context.contentResolver.openOutputStream(Uri.parse(uri), "wt") },
        clock = clock,
        io = io,
    )

    private val files by lazy { CrashReportFiles(directory()) }

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
            val stream = openOutput(destinationUri) ?: return@runCatching false
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
