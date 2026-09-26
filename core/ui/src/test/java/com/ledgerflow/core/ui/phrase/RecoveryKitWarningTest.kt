package com.ledgerflow.core.ui.phrase

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The D-07 warning is the whole mitigation for a plaintext Recovery Kit, so
 * what it promises is pinned as text. ADR-0028 rule 5 — a camera reads the PDF's
 * code — was missing from the first version of the PDF path.
 */
class RecoveryKitWarningTest {

    @Test
    fun thePdf_saysACameraCanReadIt() {
        val body = recoveryKitWarning("PDF", hasQrCode = true)

        assertThat(body).contains("QR code")
        assertThat(body).contains("a photo of the page is as good as the words")
    }

    @Test
    fun theTextFile_claimsNoQrCode() {
        assertThat(recoveryKitWarning("text file", hasQrCode = false)).doesNotContain("QR")
    }

    @Test
    fun both_sayPlainTextNotEncrypted_andWhereItGoes() {
        for (body in listOf(recoveryKitWarning("PDF", true), recoveryKitWarning("text file", false))) {
            assertThat(body).contains("in plain text")
            assertThat(body).contains("not encrypted")
            assertThat(body).contains("shared storage")
        }
    }
}
