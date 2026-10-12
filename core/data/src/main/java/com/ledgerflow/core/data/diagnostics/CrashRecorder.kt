package com.ledgerflow.core.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.core.content.pm.PackageInfoCompat
import com.ledgerflow.core.domain.diagnostics.CrashReportFormat
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The uncaught-exception handler (SPEC.md §8 BUG7(c)), installed once from
 * `LedgerFlowApplication.onCreate`.
 *
 * It writes a scrubbed report and then hands the crash to the handler that was
 * there before, so Android still shows its dialog and ends the process as it
 * would have. It never swallows a crash: an app that keeps running after an
 * unknown failure in a money ledger is worse than one that stops.
 *
 * **Nothing here may throw past the previous handler.** A failure to write the
 * report is ignored, because the original crash is the one that matters, and a
 * second exception raised from inside this handler would replace it.
 */
public object CrashRecorder {

    public fun install(context: Context) {
        val appVersion = appVersion(context)
        val files = CrashReportFiles(File(context.filesDir, CrashReportFiles.DIRECTORY))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(files, thread, throwable, appVersion)
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Anything at all: see the KDoc.
    private fun record(files: CrashReportFiles, thread: Thread, throwable: Throwable, appVersion: String) {
        // The process is ending; StrictMode's penaltyDeath (debug) would turn
        // this one disk write into a second crash on the main thread.
        val policy = StrictMode.allowThreadDiskWrites()
        try {
            val now = System.currentTimeMillis()
            files.write(
                atMillis = now,
                text = CrashReportFormat.format(
                    throwable = throwable,
                    thread = thread.name,
                    appVersion = appVersion,
                    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    atUtc = UTC.format(Instant.ofEpochMilli(now)),
                ),
            )
        } catch (_: Throwable) {
            // Intentionally ignored; see the KDoc.
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /** "0.1.0 (1)", read once at install so the handler does no IPC while dying. */
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")

    private val UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
}
