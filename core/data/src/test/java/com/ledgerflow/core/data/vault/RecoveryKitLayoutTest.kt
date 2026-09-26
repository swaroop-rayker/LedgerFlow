package com.ledgerflow.core.data.vault

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * BUG36 — on the owner's printed kit the QR code sat on top of the "How to
 * restore" sentences. The rules that make that impossible, checked with a
 * stand-in font (a fixed width per character, a little wider than the kit's
 * real 11 pt). `RecoveryKitQrTest` repeats the checks with Android's own `Paint`
 * on the device.
 */
class RecoveryKitLayoutTest {

    /** Roughly 11 pt Helvetica's average advance, rounded up. */
    private val measure: (String) -> Float = { it.length * 6.5f }

    private val section = RecoveryKitLayout.restoreSection(
        top = 460f,
        steps = RecoveryKitWriter.RESTORE_STEPS,
        measure = measure,
    )

    @Test
    fun Bug36_theCodeStartsBelowTheLastStep() {
        val lastBaseline = section.steps.maxOf { it.baseline }

        assertWithMessage("code top ${section.qrTop} vs last step baseline $lastBaseline")
            .that(section.qrTop).isGreaterThan(lastBaseline + RecoveryKitLayout.LINE_HEIGHT)
        assertThat(section.qrTop).isGreaterThan(section.caption.baseline)
    }

    @Test
    fun everyStepLine_fitsTheTextWidth() {
        for (line in section.steps) {
            val right = line.x + measure(line.text)
            assertWithMessage("\"${line.text}\" ends at $right")
                .that(right).isAtMost(RecoveryKitLayout.MARGIN + RecoveryKitLayout.TEXT_WIDTH)
        }
    }

    /** The longest step is the one that ran under the code; it must now wrap. */
    @Test
    fun theLongestStep_wraps_andLosesNoWords() {
        val longest = RecoveryKitWriter.RESTORE_STEPS.maxBy { it.length }
        val lines = RecoveryKitLayout.wrap(longest, RecoveryKitLayout.TEXT_WIDTH - measure("2. "), measure)

        assertThat(lines.size).isGreaterThan(1)
        assertThat(lines.joinToString(" ")).isEqualTo(longest)
    }

    @Test
    fun continuationLines_hangUnderTheStepsText_notItsNumber() {
        val continued = section.steps.filter { it.x > RecoveryKitLayout.MARGIN }

        assertThat(continued).isNotEmpty()
        continued.forEach { assertThat(it.text.first().isDigit()).isFalse() }
    }

    @Test
    fun theWholeSection_staysOnThePage() {
        assertThat(section.bottom).isAtMost(RecoveryKitLayout.PAGE_HEIGHT - RecoveryKitLayout.MARGIN)
        assertThat(section.qrLeft + section.qrSize).isAtMost(RecoveryKitLayout.PAGE_WIDTH - RecoveryKitLayout.MARGIN)
    }

    @Test
    fun aWordWiderThanTheLine_isKeptWhole_notCut() {
        val lines = RecoveryKitLayout.wrap("a " + "x".repeat(100) + " b", maxWidth = 60f, measure = measure)

        assertThat(lines).containsExactly("a", "x".repeat(100), "b").inOrder()
    }
}
