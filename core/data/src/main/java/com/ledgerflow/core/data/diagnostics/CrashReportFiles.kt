package com.ledgerflow.core.data.diagnostics

import java.io.File

/**
 * The crash reports on disk (BUG7(c)): `filesDir/crash/`, one `.txt` per crash.
 *
 * `filesDir`, never `cacheDir` (Law 5): the system may clear the cache, and a
 * report it cleared before the user saw it was lost for nothing. Plain text
 * and not encrypted, on the owner's decision (2026-10-12): what goes in is
 * built by `CrashReportFormat` to hold no personal data.
 *
 * Written from an uncaught-exception handler, in a process that is about to
 * die, so [write] is a temp file renamed into place: a crash *while* writing
 * leaves a stray `.tmp`, never a half report that reads as a whole one.
 */
public class CrashReportFiles(private val directory: File) {

    /** Keeps the newest [KEEP]; a crash loop must not fill the phone. */
    public fun write(atMillis: Long, text: String) {
        directory.mkdirs()
        val name = "crash-$atMillis.txt"
        val tmp = File(directory, "$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(File(directory, name))) tmp.delete()
        reports().drop(KEEP).forEach { it.delete() }
    }

    /** Newest first. Stray `.tmp` files from an interrupted write are not reports. */
    public fun reports(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith("crash-") && file.extension == "txt" }
            .orEmpty()
            .sortedByDescending { it.timeOf() }

    /** When the newest was written, from its name. */
    public fun latestAt(): Long? = reports().firstOrNull()?.timeOf()

    public fun deleteAll() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun File.timeOf(): Long = name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L

    public companion object {
        public const val KEEP: Int = 5
        public const val DIRECTORY: String = "crash"
    }
}
