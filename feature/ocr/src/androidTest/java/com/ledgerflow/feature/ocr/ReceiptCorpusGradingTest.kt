package com.ledgerflow.feature.ocr

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.feature.ocr.capture.PdfTextLayer
import com.ledgerflow.feature.ocr.extraction.ReceiptDates
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import com.ledgerflow.feature.ocr.extraction.ReceiptGrading
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The extractor graded against the real corpus** (SPEC.md §12), on the
 * device, the way the app reads a receipt: the PDF's text layer through
 * [PdfTextLayer], [ReceiptExtractor] and [ReceiptDates.apply] — the same
 * composition the capture screen calls.
 *
 * Per receipt, never pooled: **item recall and precision** by
 * [ReceiptGrading]'s definition (name after normalisation *and* exact total),
 * the **bill total exact**, and the **bill date**. The ground truth is the
 * owner's transcription, verified against the PDFs on 2026-09-19.
 *
 * **The ≥90% / ≥95% floors are provisional** until the corpus reaches the size
 * §12 names; with two receipts they are a regression guard, not a measurement,
 * and the printed figures say which receipt moved.
 *
 * **The corpus is private and is not in this repository**, so the test runs
 * only where it has been copied into this test app's own storage, and is
 * skipped elsewhere, as `ReceiptCorpusTest` is. From the repo, with the phone
 * connected and the test APK installed (`:feature:ocr:installSmsFullDebugAndroidTest`):
 *
 * ```
 * adb shell run-as com.ledgerflow.feature.ocr.test mkdir -p files/corpus
 * for f in $(ls ../LedgerFlow-receipts | grep -E '[.](pdf|json)$'); do
 *   adb exec-in run-as com.ledgerflow.feature.ocr.test sh -c "cat > files/corpus/$f" < "../LedgerFlow-receipts/$f"
 * done
 * ```
 *
 * and `rm -rf files/corpus` the same way afterwards. The app's own storage,
 * not shared storage: the receipts carry the owner's name and address.
 *
 * The capture is taken as the bill's own day, so rule (d)'s window is not
 * what is being graded; `ReceiptDatesTest` pins the window.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptCorpusGradingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.systemDefault()

    private class Receipt(val name: String, val truth: JSONObject, val read: ReceiptDates.Dated)

    /** Every real receipt with ground truth, read as the app reads it. */
    private fun receipts(): List<Receipt> {
        val corpus = File(context.filesDir, "corpus")
        val fixtures = corpus.listFiles { f -> f.extension == "json" }.orEmpty()
            .map { it to JSONObject(it.readText()) }
            .filter { (_, json) -> json.optString("provenance") == "real" }
            .sortedBy { (file, _) -> file.name }
        assumeTrue("private receipt corpus not copied to this device", fixtures.isNotEmpty())

        return fixtures.map { (file, json) ->
            val page = PdfTextLayer(context).read(Uri.fromFile(File(corpus, json.getString("image"))))
            assertWithMessage("${file.name}: the PDF's text layer did not read").that(page).isNotNull()
            requireNotNull(page)
            val day = json.optString("date").takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: LocalDate.now(zone)
            val capturedAt = day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
            Receipt(file.name, json, ReceiptDates.apply(ReceiptExtractor.extract(page), page, capturedAt, zone))
        }
    }

    @Test
    fun everyRealReceipt_meetsTheItemGate_andItsTotalIsExact() {
        receipts().forEach { receipt ->
            val lines = receipt.truth.getJSONArray("lines")
            val expected = (0 until lines.length()).map { lines.getJSONObject(it) }
                .filter { it.optString("kind", "ITEM") == "ITEM" }
                .map { ReceiptGrading.ExpectedItem(it.getString("name"), it.getLong("totalMinor")) }
            val grade = ReceiptGrading.grade(expected, receipt.read.extracted.lines)
            println(
                "GRADE ${receipt.name}: recall ${grade.hits}/${grade.expected}, " +
                    "precision ${grade.hits}/${grade.extracted}, " +
                    "total ${receipt.read.extracted.amount?.minor} vs ${receipt.truth.getLong("billTotalMinor")}",
            )
            grade.missed.forEach { println("GRADE   missed: ${it.name} ${it.totalMinor}") }
            grade.spurious.forEach { println("GRADE   spurious: ${it.name} ${it.total?.minor}") }

            assertWithMessage("${receipt.name}: item recall (provisional floor)")
                .that(grade.recall).isAtLeast(ReceiptGrading.RECALL_FLOOR)
            assertWithMessage("${receipt.name}: item precision (provisional floor)")
                .that(grade.precision).isAtLeast(ReceiptGrading.PRECISION_FLOOR)
            assertWithMessage("${receipt.name}: the bill total, which gets no tolerance")
                .that(receipt.read.extracted.amount?.minor).isEqualTo(receipt.truth.getLong("billTotalMinor"))
        }
    }

    @Test
    fun everyRealReceipt_isDatedAsItsGroundTruthSays() {
        val dated = receipts().filter { it.truth.has("date") }
        assumeTrue("no real receipt carries a date", dated.isNotEmpty())
        dated.forEach { receipt ->
            val expected = LocalDate.parse(receipt.truth.getString("date"))
            println("GRADE ${receipt.name}: date expected $expected, detected ${receipt.read.detection}")
            assertWithMessage("${receipt.name}: the bill date")
                .that((receipt.read.detection as? ReceiptDates.Detection.Found)?.date).isEqualTo(expected)
            assertWithMessage("${receipt.name}: the candidate's occurredAt")
                .that(receipt.read.extracted.occurredAt).isEqualTo(ReceiptDates.startOfDayMillis(expected, zone))
        }
    }
}
