package com.ledgerflow.core.data.vault

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.domain.vault.RecoveryKitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Writes the Recovery Kit through SAF (SPEC.md §7.2, D-07).
 *
 * Two formats because they serve different habits: the text file is what goes
 * into a password manager, the PDF is what gets printed and put somewhere
 * physical. Either one satisfies §7.4 step 4.
 *
 * The kit is deliberately **not** encrypted -- see [RecoveryKitRepository] for
 * why -- so the words appear in the file exactly as the user must type them
 * back. The screen that calls this is responsible for the confirmation dialog;
 * this class does not second-guess a decision the user has already been asked
 * about, but it also never writes anything the caller did not explicitly ask for.
 */
@Singleton
public class RecoveryKitWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RecoveryKitRepository {

    override suspend fun write(
        uri: String,
        format: RecoveryKitFormat,
        mnemonic: List<String>,
    ): Boolean = withContext(io) {
        runCatching {
            // "wt" truncates. SAF hands back an existing document when the user
            // picks a name that is already there, and without truncation a
            // shorter payload would leave the tail of the previous file behind.
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { stream ->
                when (format) {
                    RecoveryKitFormat.Text -> writeText(stream, mnemonic)
                    RecoveryKitFormat.Pdf -> writePdf(stream, mnemonic)
                }
                true
            } ?: false
        }.getOrDefault(false)
    }

    override fun suggestedFileName(format: RecoveryKitFormat): String =
        "LedgerFlow-Recovery-Kit-${today()}.${format.extension}"

    private fun writeText(stream: OutputStream, mnemonic: List<String>) {
        val body = buildString {
            appendLine(TITLE)
            appendLine("=".repeat(TITLE.length))
            appendLine()
            appendLine("Created: ${today()}")
            appendLine()
            WARNING_LINES.forEach { appendLine(it) }
            appendLine()
            appendLine("YOUR 24 WORDS")
            appendLine()
            mnemonic.forEachIndexed { index, word ->
                // Padded so a printed or pasted copy stays in columns, and so a
                // transcription error is easy to spot against the numbering.
                appendLine("${(index + 1).toString().padStart(2)}. $word")
            }
            appendLine()
            appendLine("HOW TO RESTORE")
            RESTORE_STEPS.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
        stream.write(body.toByteArray(Charsets.UTF_8))
    }

    private fun writePdf(stream: OutputStream, mnemonic: List<String>) {
        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create(),
            )
            val canvas = page.canvas
            var y = MARGIN + TITLE_SIZE

            canvas.drawText(TITLE, MARGIN, y, titlePaint)
            y += LINE_HEIGHT * 2
            canvas.drawText("Created: ${today()}", MARGIN, y, bodyPaint)
            y += LINE_HEIGHT * 2

            WARNING_LINES.forEach { line ->
                canvas.drawText(line, MARGIN, y, warningPaint)
                y += LINE_HEIGHT
            }
            y += LINE_HEIGHT

            // Two columns of twelve: 24 monospaced words down a single column
            // runs off an A4 page once the instructions are on it too.
            val columnRows = (mnemonic.size + 1) / 2
            val top = y
            mnemonic.forEachIndexed { index, word ->
                val column = index / columnRows
                val row = index % columnRows
                canvas.drawText(
                    "${(index + 1).toString().padStart(2)}.  $word",
                    MARGIN + column * COLUMN_WIDTH,
                    top + row * LINE_HEIGHT,
                    wordPaint,
                )
            }
            y = top + columnRows * LINE_HEIGHT + LINE_HEIGHT

            canvas.drawText("How to restore", MARGIN, y, headingPaint)
            y += LINE_HEIGHT
            RESTORE_STEPS.forEachIndexed { index, step ->
                canvas.drawText("${index + 1}. $step", MARGIN, y, bodyPaint)
                y += LINE_HEIGHT
            }

            document.finishPage(page)
            document.writeTo(stream)
        } finally {
            document.close()
        }
    }

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = TITLE_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val headingPaint = Paint().apply {
        color = Color.BLACK
        textSize = HEADING_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val bodyPaint = Paint().apply {
        color = Color.BLACK
        textSize = BODY_SIZE
        isAntiAlias = true
    }

    private val warningPaint = Paint().apply {
        color = Color.rgb(WARNING_R, WARNING_G, WARNING_B)
        textSize = BODY_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    /** Monospaced so a hand-transcribed word can be compared character by character. */
    private val wordPaint = Paint().apply {
        color = Color.BLACK
        textSize = BODY_SIZE
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private companion object {
        private const val TITLE = "LedgerFlow Recovery Kit"

        /** A4 at 72 dpi, in points. */
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 48f
        private const val COLUMN_WIDTH = 240f
        private const val LINE_HEIGHT = 18f
        private const val TITLE_SIZE = 22f
        private const val HEADING_SIZE = 14f
        private const val BODY_SIZE = 11f
        private const val WARNING_R = 176
        private const val WARNING_G = 0
        private const val WARNING_B = 32

        private val WARNING_LINES = listOf(
            "ANYONE WHO HAS THESE 24 WORDS CAN READ EVERY BACKUP THIS APP EVER WRITES.",
            "This file is not encrypted. Store it the way you would store a spare house key.",
            "LedgerFlow has no copy of these words and no way to reset them.",
        )

        /**
         * Worded around what the words *are*, not which button to press.
         *
         * A Recovery Kit is kept for years. Naming a specific screen would date
         * the file the first time the flow is reworded, and a user following
         * stale instructions during a recovery is a user who concludes they have
         * the wrong file.
         */
        private val RESTORE_STEPS = listOf(
            "Install LedgerFlow on the device.",
            "When LedgerFlow cannot unlock by itself, it asks for these 24 words. " +
                "Type them in order.",
            "The same 24 words also decrypt any .lfbk backup file this install " +
                "has written, on any device.",
            "The words are the only copy. LedgerFlow cannot reissue them.",
        )
    }
}
