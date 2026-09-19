package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.OccurredAt
import com.ledgerflow.feature.ocr.capture.dateSentence
import com.ledgerflow.feature.ocr.extraction.ReceiptDates.Detection
import com.ledgerflow.feature.ocr.extraction.ReceiptDates.Refusal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/**
 * §5.3's four date rules (owner, 2026-09-19), one group per rule, plus the two
 * rows measured off the owner's invoices on the device. Every case here that is
 * not one of those two rows is **synthetic** — two receipts are two layouts,
 * and the other common Indian formats are covered by construction, not by
 * measurement.
 */
class ReceiptDatesTest {

    private val capturedOn = LocalDate.of(2026, 9, 12)

    private fun detect(vararg rows: String) = ReceiptDates.detect(rows.toList(), capturedOn)

    private fun found(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).let { date -> Detection.Found(date, "").date }

    private fun Detection.day(): LocalDate? = (this as? Detection.Found)?.date

    // ── Measured on the device ────────────────────────────────────────────

    /** Zepto: the date shares its row with the order number, and its colon is gone. */
    @Test
    fun zepto_asTheTextLayerDeliversIt() {
        assertThat(detect("Order No.: RGLOJVYNN22994A Date 20-07-2026").day()).isEqualTo(found(2026, 7, 20))
    }

    /** bigbasket: the invoice date wins; the slot and payment dates carry no label. */
    @Test
    fun bigbasket_asTheTextLayerDeliversIt() {
        val detection = detect(
            "Invoice Date 2026-09-12",
            "Slot Sat 12 Sep 2026 between",
            "0d90f593ef24a57e4c3a Debit availed from Wallet on 2026-09-12 Rs.1443.41",
        )
        assertThat(detection.day()).isEqualTo(found(2026, 9, 12))
        assertThat((detection as Detection.Found).printed).isEqualTo("2026-09-12")
    }

    // ── (a) the bill's own date ───────────────────────────────────────────

    @Test
    fun a_aBillDate_beatsAnEarlierGenericOne() {
        assertThat(detect("Date 01/09/2026", "Invoice Date 05/09/2026").day()).isEqualTo(found(2026, 9, 5))
    }

    @Test
    fun a_withoutABillDate_theEarliestGenericDateIsTaken() {
        assertThat(detect("Date 10/09/2026", "Date 08/09/2026").day()).isEqualTo(found(2026, 9, 8))
    }

    @Test
    fun a_anExpiryOrManufactureDate_isNeverTaken() {
        assertThat(detect("Exp 12/12/2026", "Best Before 01/01/2027", "Mfg Date 01/08/2026", "Due Date 20/09/2026"))
            .isEqualTo(Detection.None)
        assertThat(detect("Mfg Date 01/08/2026 Date 11/09/2026").day()).isEqualTo(found(2026, 9, 11))
    }

    @Test
    fun a_anUnlabelledDate_isNeverTaken() {
        assertThat(detect("Sat 12 Sep 2026", "Payment on 2026-09-12")).isEqualTo(Detection.None)
    }

    /** Whole words (BUG24): `Update` is not a `Date` label. */
    @Test
    fun a_aLabelIsAWholeWord() {
        assertThat(detect("Update 11/09/2026")).isEqualTo(Detection.None)
    }

    // ── (b) midnight, like every time-less source ─────────────────────────

    @Test
    fun b_theDayIsStoredAtLocalMidnight_andReadsAsTimeless() {
        val zone = ZoneId.of("Asia/Kolkata")
        val millis = ReceiptDates.startOfDayMillis(LocalDate.of(2026, 9, 12), zone)
        val stored = Instant.ofEpochMilli(millis).atZone(zone)

        assertThat(stored.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12))
        assertThat(stored.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT)
        // So the shared rule treats it as "no clock" and shows capture's.
        val captured = LocalDate.of(2026, 9, 13).atTime(15, 30).atZone(zone).toInstant().toEpochMilli()
        val shown = Instant.ofEpochMilli(OccurredAt.effective(millis, captured, zone)).atZone(zone)
        assertThat(shown.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12))
        assertThat(shown.toLocalTime()).isEqualTo(LocalTime.of(15, 30))
    }

    // ── (c) day first; ISO when the year leads ────────────────────────────

    @Test
    fun c_aNumericDate_isDayFirst() {
        // 3 April, not 4 March.
        assertThat(detect("Date 03/04/2026").day()).isEqualTo(found(2026, 4, 3))
        assertThat(detect("Date 11/09/26").day()).isEqualTo(found(2026, 9, 11))
        assertThat(detect("Date 11.09.2026").day()).isEqualTo(found(2026, 9, 11))
    }

    @Test
    fun c_aLeadingFourDigitYear_isIso() {
        assertThat(detect("Bill Date 2026/09/11").day()).isEqualTo(found(2026, 9, 11))
    }

    @Test
    fun c_monthNames_inEitherOrder() {
        assertThat(detect("Date: 11 Sep 2026").day()).isEqualTo(found(2026, 9, 11))
        assertThat(detect("Dated 11th September, 2026").day()).isEqualTo(found(2026, 9, 11))
        assertThat(detect("Date Sep 11, 2026").day()).isEqualTo(found(2026, 9, 11))
        assertThat(detect("Date 11-Sep-26").day()).isEqualTo(found(2026, 9, 11))
    }

    @Test
    fun c_anImpossibleDay_isNotADate() {
        assertThat(detect("Date 31/02/2026")).isEqualTo(Detection.None)
    }

    /** The traps: a word that starts like a month, an amount, a percentage. */
    @Test
    fun c_nothingThatMerelyLooksLikeADate_isRead() {
        assertThat(ReceiptDates.candidates("Margin 12 2026 Date 2.50% 12.09 1776.17")).isEmpty()
    }

    // ── (d) a plausible day or none ───────────────────────────────────────

    @Test
    fun d_oneDayAfterCapture_isAllowed_twoIsNot() {
        assertThat(detect("Date 13/09/2026").day()).isEqualTo(found(2026, 9, 13))
        assertThat(detect("Date 14/09/2026"))
            .isEqualTo(Detection.Refused(LocalDate.of(2026, 9, 14), "14/09/2026", Refusal.AFTER_CAPTURE))
    }

    @Test
    fun d_aYearBefore_isAllowed_moreIsNot() {
        assertThat(detect("Date 12/09/2025").day()).isEqualTo(found(2025, 9, 12))
        assertThat(detect("Date 11/09/2025"))
            .isEqualTo(Detection.Refused(LocalDate.of(2025, 9, 11), "11/09/2025", Refusal.TOO_OLD))
    }

    /** A refused bill date is evidence of a misread; a weaker date is not tried in its place. */
    @Test
    fun d_aRefusedBillDate_isNotReplacedByAnotherDate() {
        assertThat(detect("Invoice Date 14/10/2026", "Date 10/09/2026"))
            .isInstanceOf(Detection.Refused::class.java)
    }

    // ── What the capture screen says ──────────────────────────────────────

    @Test
    fun theSummary_saysWhatWasReadOrWhyNothingWas() {
        assertThat(dateSentence(Detection.Found(LocalDate.of(2026, 9, 12), "2026-09-12"))).startsWith("Dated ")
        assertThat(dateSentence(Detection.Refused(LocalDate.of(2019, 9, 12), "12/09/2019", Refusal.TOO_OLD)))
            .contains("over a year ago")
        assertThat(dateSentence(Detection.None)).contains("check it when you review")
    }
}
