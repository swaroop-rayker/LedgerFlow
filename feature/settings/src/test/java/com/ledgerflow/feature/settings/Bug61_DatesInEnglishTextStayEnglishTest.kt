package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import com.ledgerflow.feature.settings.diagnostics.rollupChecked
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **BUG61, second half: a date in an English sentence was formatted for the
 * phone's language.**
 *
 * Found on the device (SM-S721B, the app's locale set to Arabic; More was not yet Settings) after the
 * full-stop fix: More's backup row read "Last backup 212026/10/." Arabic's date
 * format is "21\u200F/10\u200F/2026" with a right-to-left mark after the day and the
 * month, and inside an English (left-to-right) sentence those marks reorder
 * it. Patterns that spell the month would have put "أكتوبر" in the same
 * sentence.
 *
 * Owner's decision (2026-10-21): while the app's text is English only, dates
 * and month names are formatted in English, in the phone's region
 * (`DisplayLocale`). These are the sentences that showed it, formatted with the
 * device default set to Arabic, as the phone had it.
 */
class Bug61_DatesInEnglishTextStayEnglishTest {

    private lateinit var hostLocale: Locale

    @Before
    fun arabicPhone() {
        hostLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
    }

    @After
    fun restore() {
        Locale.setDefault(hostLocale)
    }

    private fun assertEnglishOnly(sentence: String) {
        val offenders = sentence.filter { char ->
            char in BIDI_MARKS || Character.UnicodeBlock.of(char) == Character.UnicodeBlock.ARABIC
        }
        assertWithMessage("\"$sentence\" carries right-to-left marks or Arabic letters")
            .that(offenders.map { "U+%04X".format(it.code) }).isEmpty()
    }

    @Test
    fun settingsRow_lastBackupDate_isEnglish() =
        assertEnglishOnly(backupSubtitle(SettingsUiState(lastBackupAt = OCT_21_2026)))

    @Test
    fun diagnostics_rollupDate_isEnglish() =
        assertEnglishOnly(rollupChecked(RollupHealth(lastReconciledAt = OCT_21_2026, bucketsRepaired = 0)))

    private companion object {
        const val OCT_21_2026 = 1_792_540_800_000L
        val BIDI_MARKS = setOf('\u200E', '\u200F', '\u061C')
    }
}
