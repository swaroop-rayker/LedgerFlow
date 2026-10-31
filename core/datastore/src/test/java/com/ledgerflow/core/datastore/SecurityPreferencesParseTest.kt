package com.ledgerflow.core.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import org.junit.Test

/** How the security file reads back (ADR-0030): a missing or damaged value is the app as it always was. */
class SecurityPreferencesParseTest {

    @Test
    fun anEmptyFile_isLockOff_screenshotsAllowed_amountsShown() {
        assertThat(emptyPreferences().toSecurity()).isEqualTo(SecurityPreferences())
    }

    @Test
    fun storedValues_readBack() {
        val prefs = mutablePreferencesOf(
            SecurityDataStore.APP_LOCK to true,
            SecurityDataStore.LOCK_AFTER to "ONE_MINUTE",
            SecurityDataStore.BLOCK_SCREENSHOTS to true,
            SecurityDataStore.HIDE_AMOUNTS to true,
        )
        assertThat(prefs.toSecurity()).isEqualTo(
            SecurityPreferences(
                appLock = true,
                lockAfter = LockTimeout.ONE_MINUTE,
                blockScreenshots = true,
                hideAmounts = true,
            ),
        )
    }

    @Test
    fun anUnknownTimeout_isTheDefault_andTheLockStaysAsStored() {
        val prefs = mutablePreferencesOf(
            SecurityDataStore.APP_LOCK to true,
            SecurityDataStore.LOCK_AFTER to "TEN_YEARS",
        )
        assertThat(prefs.toSecurity()).isEqualTo(SecurityPreferences(appLock = true))
    }
}
