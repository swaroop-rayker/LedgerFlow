package com.ledgerflow.feature.ocr

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.feature.ocr.capture.PdfTextLayer
import com.ledgerflow.feature.ocr.extraction.ReceiptDates
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The first test that grades the extractor against the real corpus** — for
 * the bill date (SPEC.md §5.3, the owner's four rules of 2026-09-19).
 *
 * Every real receipt in the private store (`../LedgerFlow-receipts`) whose
 * ground truth carries a `date` is read the way the app reads it — the PDF's
 * text layer through [PdfTextLayer], the same [ReceiptDates.apply] the capture
 * screen calls — and the detected day must equal the transcribed one, which
 * the owner verified against the PDFs on 2026-09-19.
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
 * what is being graded here; `ReceiptDatesTest` pins the window.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptCorpusDateGradingTest {

    @Test
    fun everyRealReceipt_isDatedAsItsGroundTruthSays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpus = File(context.filesDir, "corpus")
        val fixtures = corpus.listFiles { f -> f.extension == "json" }.orEmpty()
            .map { it to JSONObject(it.readText()) }
            .filter { (_, json) -> json.optString("provenance") == "real" && json.has("date") }
        assumeTrue("private receipt corpus not copied to this device", fixtures.isNotEmpty())

        val zone = ZoneId.systemDefault()
        fixtures.forEach { (file, json) ->
            val expected = LocalDate.parse(json.getString("date"))
            val pdf = File(corpus, json.getString("image"))
            val page = PdfTextLayer(context).read(Uri.fromFile(pdf))
            assertWithMessage("${file.name}: the PDF's text layer did not read").that(page).isNotNull()
            requireNotNull(page)

            val capturedAt = expected.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
            val dated = ReceiptDates.apply(ReceiptExtractor.extract(page), page, capturedAt, zone)
            println("GRADE ${file.name}: expected $expected, detected ${dated.detection}")

            assertWithMessage("${file.name}: the bill date")
                .that((dated.detection as? ReceiptDates.Detection.Found)?.date).isEqualTo(expected)
            assertWithMessage("${file.name}: the candidate's occurredAt")
                .that(dated.extracted.occurredAt).isEqualTo(ReceiptDates.startOfDayMillis(expected, zone))
        }
        println("GRADE ${fixtures.size} real receipt(s) dated correctly")
    }
}
