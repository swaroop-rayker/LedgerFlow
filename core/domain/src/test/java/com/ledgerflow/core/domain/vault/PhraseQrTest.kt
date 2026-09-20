package com.ledgerflow.core.domain.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The QR carrier for a phrase (ADR-0028).
 *
 * The phrase throughout is the **public BIP-39 test vector** — `abandon` ×23
 * then `art`. It is nobody's key, and no real phrase appears in a test.
 */
class PhraseQrTest {

    private val words = List(23) { "abandon" } + "art"

    @Test
    fun aKitsCode_readsBackAsTheWordsItCarries() {
        val scan = PhraseQr.decode(PhraseQr.encode(words))

        assertThat(scan).isEqualTo(PhraseQr.Scan.Phrase(words))
    }

    /** A code from anywhere else is refused, not half-parsed into the field. */
    @Test
    fun someoneElsesCode_isNotOurs() {
        listOf(
            "https://example.com/pay?amount=100",
            "WIFI:S:home;T:WPA;P:hunter2;;",
            "abandon abandon art",
            "",
        ).forEach { foreign ->
            assertThat(PhraseQr.decode(foreign)).isEqualTo(PhraseQr.Scan.NotOurs)
        }
    }

    /** Ours, from a format this build does not read: the user is told which, not "not ours". */
    @Test
    fun aNewerKitsCode_saysItIsNewer() {
        val scan = PhraseQr.decode("LFBK2:${words.joinToString(" ")}")

        assertThat(scan).isInstanceOf(PhraseQr.Scan.Unreadable::class.java)
        assertThat((scan as PhraseQr.Scan.Unreadable).reason).contains("newer version")
    }

    @Test
    fun ourPrefixWithNothingBehindIt_isUnreadable() {
        assertThat(PhraseQr.decode("${PhraseQr.PREFIX}   "))
            .isInstanceOf(PhraseQr.Scan.Unreadable::class.java)
    }

    /** A printed code that picked up line breaks is still the user's phrase. */
    @Test
    fun oddWhitespace_doesNotCostTheUserTheirScan() {
        val awkward = " ${PhraseQr.PREFIX}${words.joinToString("\n  ")}\n"

        assertThat(PhraseQr.decode(awkward)).isEqualTo(PhraseQr.Scan.Phrase(words))
    }

    // ─── What a scan does to the field ──────────────────────────────────────

    private val validator = object : RecoveryPhraseValidator {
        override val wordCount: Int = 24
        override fun warmUp() = Unit
        override fun validate(words: List<String>): PhraseValidation = PhraseValidation.Valid
        override fun isKnownWord(word: String): Boolean = true
        override fun suggestions(prefix: String, limit: Int): List<String> = emptyList()
        override fun parse(input: String): List<String> = input.split(" ").filter { it.isNotBlank() }
    }

    private val empty = PhraseEntry(requiredWordCount = 24)

    @Test
    fun scanningAKit_fillsTheField() {
        val applied = empty.applyScan(PhraseQr.encode(words), validator)

        assertThat(applied).isEqualTo(PhraseScan.Filled(empty.copy(words = words)))
    }

    /** Someone else's QR is not the user's mistake: the camera stays open. */
    @Test
    fun scanningSomethingElse_keepsLooking() {
        assertThat(empty.applyScan("https://example.com", validator)).isEqualTo(PhraseScan.KeepLooking)
    }

    @Test
    fun scanningANewerKit_isRejectedWithASentence() {
        val applied = empty.applyScan("LFBK2:${words.joinToString(" ")}", validator)

        assertThat(applied).isInstanceOf(PhraseScan.Rejected::class.java)
        assertThat((applied as PhraseScan.Rejected).message).startsWith("That Recovery Kit was made by a newer")
    }

    /** A scan replaces whatever was half-typed, exactly as a paste does. */
    @Test
    fun scanningOverAHalfTypedPhrase_replacesIt() {
        val halfTyped = empty.copy(words = listOf("abandon", "ability"), draft = "abl")

        val applied = halfTyped.applyScan(PhraseQr.encode(words), validator)

        assertThat((applied as PhraseScan.Filled).entry.words).isEqualTo(words)
        assertThat(applied.entry.draft).isEmpty()
    }

    /**
     * Word validation stays with the validator: this layer hands the words on
     * exactly as scanned, so a mis-scan is answered by the same checksum
     * message a typo gets.
     */
    @Test
    fun theWordsAreNotJudgedHere() {
        val nonsense = listOf("qqq", "zzz")

        assertThat(PhraseQr.decode(PhraseQr.encode(nonsense)))
            .isEqualTo(PhraseQr.Scan.Phrase(nonsense))
    }
}
