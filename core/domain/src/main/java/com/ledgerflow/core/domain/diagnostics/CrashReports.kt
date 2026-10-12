package com.ledgerflow.core.domain.diagnostics

import kotlinx.coroutines.flow.Flow

/**
 * What Home needs to offer the reports (BUG7(c)): how many, and the latest.
 */
public data class CrashSummary(
    val count: Int,
    val latestAt: Long,
)

/**
 * Crash reports kept on the device until the user saves or dismisses them
 * (SPEC.md §8 BUG7(c), owner's design 2026-10-12).
 *
 * **Scrubbed, not encrypted.** A report holds exception class names, stack
 * frames and versions -- see [CrashReportFormat] -- and never an exception's
 * message, which is where amounts, merchant names and SQL with values turn up.
 * With nothing personal in it there is nothing to encrypt, and no key material
 * is added for it (CLAUDE.md §7). Nothing is sent anywhere: saving one is the
 * user picking a file with Android's own picker (Law 6).
 */
public interface CrashReportRepository {

    /** The reports waiting, or null when there are none. */
    public val summary: Flow<CrashSummary?>

    /** Writes every report, newest first, to [destinationUri]. False if it could not. */
    public suspend fun exportTo(destinationUri: String): Boolean

    /** `LedgerFlow-crash-reports-2026-10-12.txt`. */
    public fun suggestedFileName(): String

    /** Deletes the reports. They are the user's, so only the user does this. */
    public suspend fun dismissAll()
}

/**
 * One crash as text, built so it cannot carry the user's data (BUG7(c)).
 *
 * The rule is structural rather than a filter: [format] never calls
 * [Throwable.message] or [Throwable.toString] (which embeds the message), so
 * there is no list of patterns to keep up to date and nothing to slip past one.
 * What it writes -- the class of each throwable in the cause chain, each
 * frame's class, method, file and line, the app and Android versions, the time
 * -- is code and build facts, which are the same on every phone.
 */
public object CrashReportFormat {

    /** Frames kept per throwable; a deep recursion would otherwise fill the file. */
    public const val MAX_FRAMES: Int = 60

    /** Causes followed; a cycle in a cause chain is legal and would never end. */
    public const val MAX_CAUSES: Int = 8

    public fun format(
        throwable: Throwable,
        thread: String,
        appVersion: String,
        androidVersion: String,
        atUtc: String,
    ): String = buildString {
        appendLine("LedgerFlow crash report")
        appendLine("time (UTC): $atUtc")
        appendLine("app: $appVersion")
        appendLine("android: $androidVersion")
        appendLine("thread: ${threadKind(thread)}")
        appendLine("(Exception messages are deliberately not recorded: they can contain your data.)")
        var current: Throwable? = throwable
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            appendLine(if (depth == 0) current.javaClass.name else "caused by: ${current.javaClass.name}")
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                val where = "${frame.fileName ?: "?"}:${frame.lineNumber}"
                appendLine("    at ${frame.className}.${frame.methodName}($where)")
            }
            if (current.stackTrace.size > MAX_FRAMES) {
                appendLine("    ... ${current.stackTrace.size - MAX_FRAMES} more")
            }
            current = current.cause
            depth++
        }
    }

    /**
     * "main" or "background": a thread's *name* is set by whoever made it, and
     * a library is free to put anything there.
     */
    private fun threadKind(thread: String): String = if (thread == "main") "main" else "background"
}
