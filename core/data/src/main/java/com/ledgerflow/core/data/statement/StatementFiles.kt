package com.ledgerflow.core.data.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ledgerflow.core.domain.statement.StatementGuess
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.statement.UnsupportedStatement
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

/** A picked document: its name as the picker shows it, and its bytes. */
public class StatementDocument(public val name: String, public val bytes: ByteArray)

/**
 * Reads the document the user picked. A seam, as `BackupDocumentReader` is for
 * restore, so the import is tested against bytes rather than the system picker.
 */
public fun interface StatementDocumentSource {
    /** Null when it cannot be read; a document over [limit] bytes comes back as exactly limit + 1 bytes. */
    public fun read(documentUri: String, limit: Int): StatementDocument?
}

/** The app's source: `ContentResolver`, under the grant the picker gave. */
public class SafStatementDocumentSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : StatementDocumentSource {
    override fun read(documentUri: String, limit: Int): StatementDocument? = runCatching {
        val uri = Uri.parse(documentUri)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readNBytesCompat(limit + 1)
        } ?: return null
        StatementDocument(name, bytes)
    }.getOrNull()
}

/** `readNBytes` arrived in API 33; minSdk is 26. */
private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(minOf(count, READ_BUFFER))
    var remaining = count
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}

private const val READ_BUFFER = 64 * 1024

/**
 * Turns a document into a table (V2-4 Import): by what its first bytes say it
 * is, not by its name — a `.csv` that is really an old Excel file is refused
 * with the right sentence rather than read as gibberish.
 */
internal object StatementFiles {

    /** Larger than any statement; refused rather than read slowly into memory. */
    const val MAX_BYTES: Int = 10 * 1024 * 1024

    fun open(document: StatementDocument, baseCurrency: String): StatementOpened {
        val bytes = document.bytes
        refusalOf(bytes)?.let { return it }
        val rows = if (bytes.startsWith(ZIP)) XlsxTableReader.read(bytes) else CsvTableReader.read(bytes)
        if (rows == null || rows.none { row -> row.any { it.isNotBlank() } }) return StatementOpened.Unreadable
        val table = StatementTable(name = document.name, rows = rows, fingerprint = fingerprint(bytes))
        return StatementOpened.Opened(table, StatementGuess.guess(table), baseCurrency)
    }

    /** A file refused before it is read: too big, empty, or a kind this does not read. */
    private fun refusalOf(bytes: ByteArray): StatementOpened? = when {
        bytes.size > MAX_BYTES -> StatementOpened.TooLarge
        bytes.isEmpty() -> StatementOpened.Unreadable
        bytes.startsWith(OLE) -> StatementOpened.Unsupported(UnsupportedStatement.OldExcel)
        bytes.startsWith(PDF) -> StatementOpened.Unsupported(UnsupportedStatement.Pdf)
        else -> null
    }

    /** The first 16 hex digits of the file's SHA-256: enough to tell files apart, short enough to read. */
    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte())
    private val PDF = "%PDF".toByteArray()
    private const val FINGERPRINT_BYTES = 8
}
