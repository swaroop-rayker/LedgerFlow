package com.ledgerflow.core.domain.vault

/**
 * Writes the Recovery Kit to a user-chosen location (SPEC.md §7.2, D-07).
 *
 * **The kit is plaintext, and that is the decision, not an oversight.** It holds
 * the 24 words -- the master key to every backup this install will ever write.
 * Encrypting it would need a secret to protect the secret, and any secret the
 * user chooses is weaker than the 256 bits it would be guarding. So the file
 * ships unprotected and the *tap that writes it* is gated behind a dialog that
 * says exactly that. Q8 in §16 is closed on those terms.
 *
 * URIs cross this port as `String` because `:core:domain` carries no Android
 * types; the implementation resolves them through the `ContentResolver`.
 */
public interface RecoveryKitRepository {

    /**
     * @param uri a SAF document URI the user just created via the picker.
     * @return whether the bytes landed. A failure here is not fatal to
     *   onboarding -- the words are still on screen and the user can transcribe
     *   them -- but it must be surfaced rather than swallowed, or the user
     *   believes they have a kit they do not have.
     */
    public suspend fun write(uri: String, format: RecoveryKitFormat, mnemonic: List<String>): Boolean

    /** Suggested filename for the picker, including the extension. */
    public fun suggestedFileName(format: RecoveryKitFormat): String
}

public enum class RecoveryKitFormat(public val mimeType: String, public val extension: String) {
    /** What you paste into a password manager. */
    Text("text/plain", "txt"),

    /** What you print and put in a drawer. */
    Pdf("application/pdf", "pdf"),
}
