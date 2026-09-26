package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.ui.phrase.recoveryKitWarning
import org.junit.Test

/**
 * The Recovery Kit step's main button saves the file that carries both the
 * words and the QR code (owner, 2026-09-26). It used to save the text file,
 * which cannot carry a code, so a new user taking the obvious path got nothing
 * to scan (ADR-0028).
 */
class RecoveryKitStepTest {

    @Test
    fun theMainButton_savesThePdf() {
        assertThat(PRIMARY_KIT_FORMAT).isEqualTo(RecoveryKitFormat.Pdf)
    }

    /** And the warning in front of it says the file can be read by a camera. */
    @Test
    fun theMainButtonsWarning_mentionsTheQrCode() {
        assertThat(recoveryKitWarning("PDF", hasQrCode = PRIMARY_KIT_FORMAT == RecoveryKitFormat.Pdf))
            .contains("QR code")
    }
}
