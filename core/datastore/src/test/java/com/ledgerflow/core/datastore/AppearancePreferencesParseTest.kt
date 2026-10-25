package com.ledgerflow.core.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import org.junit.Test

/**
 * How the appearance file reads back (ADR-0030).
 *
 * The flow behind it paints the very first frame, so the read must never
 * throw: an absent file, an absent key and a name this build does not know all
 * come back as the default rather than as an exception.
 */
class AppearancePreferencesParseTest {

    @Test
    fun anEmptyFile_isTheDefaults() {
        assertThat(emptyPreferences().toAppearance()).isEqualTo(AppearancePreferences())
        assertThat(AppearancePreferences()).isEqualTo(
            AppearancePreferences(ThemePreference.SYSTEM, AnimationPreference.FULL, StartScreen.HOME),
        )
    }

    @Test
    fun storedNames_readBackAsTheirValues() {
        val prefs = mutablePreferencesOf(
            AppearanceDataStore.THEME to "DARK",
            AppearanceDataStore.ANIMATIONS to "REDUCED",
            AppearanceDataStore.START_SCREEN to "ANALYTICS",
        )
        assertThat(prefs.toAppearance()).isEqualTo(
            AppearancePreferences(ThemePreference.DARK, AnimationPreference.REDUCED, StartScreen.ANALYTICS),
        )
    }

    @Test
    fun aNameThisBuildDoesNotKnow_isTheDefault_andTheOthersStillRead() {
        // A file written by a newer build, then the app downgraded.
        val prefs = mutablePreferencesOf(
            AppearanceDataStore.THEME to "SEPIA",
            AppearanceDataStore.START_SCREEN to "LEDGER",
        )
        assertThat(prefs.toAppearance()).isEqualTo(
            AppearancePreferences(startScreen = StartScreen.LEDGER),
        )
    }

    @Test
    fun everyValue_roundTripsByName() {
        ThemePreference.entries.forEach { assertThat(parseOr(it.name, ThemePreference.SYSTEM)).isEqualTo(it) }
        AnimationPreference.entries.forEach { assertThat(parseOr(it.name, AnimationPreference.FULL)).isEqualTo(it) }
        StartScreen.entries.forEach { assertThat(parseOr(it.name, StartScreen.HOME)).isEqualTo(it) }
    }
}
