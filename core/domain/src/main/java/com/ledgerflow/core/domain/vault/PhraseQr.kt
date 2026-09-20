package com.ledgerflow.core.domain.vault

/**
 * The recovery phrase, carried as a QR code (ADR-0028).
 *
 * **The carrier changes, the secret does not.** 24 words, 64 hex characters and
 * a QR are three encodings of the same 256 bits; this is the one a camera can
 * read, which is what removes the typing from a restore without weakening
 * anything (`SPEC.md` §7.2 is untouched).
 *
 * The payload is the words, space-separated, behind a short versioned prefix so
 * that a QR from somewhere else — a boarding pass, a Wi-Fi code, a payment
 * link — is **refused with a sentence** rather than half-parsed into a phrase
 * field. This layer does no BIP-39 checking: [RecoveryPhraseValidator] does
 * that, at the same place and in the same order as typed words, so a mis-scan
 * costs nothing.
 */
public object PhraseQr {

    /** Version 1 of this payload. A later format changes the digit, and old kits still read. */
    public const val PREFIX: String = "LFBK1:"

    /** What a scanned code turned out to be. */
    public sealed interface Scan {
        /** A LedgerFlow phrase code. The words are **not** validated here. */
        public data class Phrase(val words: List<String>) : Scan

        /** A QR code that is not one of ours; the scanner keeps looking. */
        public data object NotOurs : Scan

        /** Ours, and unusable — truncated, or a version this build does not know. */
        public data class Unreadable(val reason: String) : Scan
    }

    /** The payload for [words], as written into the Recovery Kit PDF. */
    public fun encode(words: List<String>): String = PREFIX + words.joinToString(" ")

    /**
     * Reads a scanned string.
     *
     * Whitespace is normalised rather than rejected: a printed code that picked
     * up a line break on its way through a PDF viewer is still the user's
     * phrase, and refusing it would be pedantry with a camera in someone's hand.
     */
    public fun decode(text: String): Scan {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return ourOtherVersion(trimmed)
        val words = trimmed.removePrefix(PREFIX).split(WHITESPACE).filter { it.isNotBlank() }
        return if (words.isEmpty()) {
            Scan.Unreadable("that code is a LedgerFlow code with no words in it")
        } else {
            Scan.Phrase(words)
        }
    }

    /**
     * A code that is ours but from a format this build does not read says so,
     * rather than reporting "not ours" and leaving the user scanning a
     * perfectly good kit forever.
     */
    private fun ourOtherVersion(text: String): Scan =
        if (OTHER_VERSION.containsMatchIn(text)) {
            Scan.Unreadable("that Recovery Kit was made by a newer version of LedgerFlow")
        } else {
            Scan.NotOurs
        }

    private val WHITESPACE = Regex("""\s+""")
    private val OTHER_VERSION = Regex("""^LFBK(\d+):""")
}

/** What a scanned code does to the field it was scanned into. */
public sealed interface PhraseScan {
    /** The words are in. Whether they are a *valid* phrase is still the validator's call, on submit. */
    public data class Filled(val entry: PhraseEntry) : PhraseScan

    /** Someone else's QR code: keep the camera open rather than blaming the user. */
    public data object KeepLooking : PhraseScan

    /** Ours and unusable. The camera closes and this sentence is shown. */
    public data class Rejected(val message: String) : PhraseScan
}

/**
 * Applies a scanned code to a phrase field (ADR-0028).
 *
 * One implementation, three callers — onboarding's Recovery screen, restore and
 * "Back up now" — because three copies of this would eventually disagree about
 * what a foreign QR does.
 *
 * Scanned words go in exactly as typed ones would, through the same
 * [PhraseEntry.paste], so a mis-scan is answered by the same checksum message a
 * typo gets rather than by a second vocabulary of errors.
 */
public fun PhraseEntry.applyScan(text: String, validator: RecoveryPhraseValidator): PhraseScan =
    when (val scan = PhraseQr.decode(text)) {
        is PhraseQr.Scan.Phrase -> PhraseScan.Filled(paste(scan.words.joinToString(" "), validator))
        PhraseQr.Scan.NotOurs -> PhraseScan.KeepLooking
        is PhraseQr.Scan.Unreadable ->
            PhraseScan.Rejected(scan.reason.replaceFirstChar(Char::uppercaseChar) + ".")
    }
