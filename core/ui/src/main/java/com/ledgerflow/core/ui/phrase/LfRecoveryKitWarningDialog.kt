package com.ledgerflow.core.ui.phrase

import androidx.compose.runtime.Composable
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis

/**
 * The D-07 confirmation, shown before any Recovery Kit is written.
 *
 * The kit is written in plaintext, and that decision was made on the basis that
 * the user is *told* so at the moment it matters. This dialog is that telling —
 * it is the entire mitigation, so it says what the file is, what it grants, and
 * where it is going, in those words.
 *
 * **One copy, two callers** — onboarding and "Back up now" — because the
 * sentence that makes the mitigation true must not drift between them.
 * [hasQrCode] adds ADR-0028's rule 5, which the first version of the PDF path
 * never said: a camera reads the code, so a photograph of the page is as good
 * as the words.
 *
 * @param fileLabel what the file is, in the user's words ("PDF", "text file").
 */
@Composable
public fun LfRecoveryKitWarningDialog(
    fileLabel: String,
    hasQrCode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LfDialog(
        title = "This file is your master key",
        body = recoveryKitWarning(fileLabel, hasQrCode),
        confirmText = "I understand — save it",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The dialog's body, separate so its promises can be tested as text. */
public fun recoveryKitWarning(fileLabel: String, hasQrCode: Boolean): String = buildString {
    append("The $fileLabel contains your 24 words in plain text — it is not encrypted. ")
    if (hasQrCode) {
        append("It also carries a QR code a camera can read, so a photo of the page is as good as the words. ")
    }
    append(
        "Anyone who opens it can read every backup this app will ever write. You're about to save " +
            "it to shared storage, which may sync to the cloud. Store it the way you'd store a spare " +
            "house key.",
    )
}
