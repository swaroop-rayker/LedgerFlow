package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.domain.security.SecurityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** `filesDir/datastore/security.preferences_pb` (Law 5). */
internal val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "security")

/**
 * Settings > Security & privacy, outside the vault (V2-PLAN §3.5, ADR-0030).
 *
 * Outside because the lock must decide before anything draws -- before the
 * vault has opened, and whatever state it is in -- and the screenshot block
 * must cover the very first frame. Device-local by nature: a lock setting
 * arriving with a restore onto someone else's phone would be wrong, so a
 * `.lfbk` does not carry it.
 *
 * Read leniently, like [AppearanceDataStore]: a damaged file reads as the
 * defaults, which are lock off and screenshots allowed -- the app as it
 * always was. That is the honest failure for a UI gate over data that stays
 * encrypted either way; failing closed could lock the user out of the screen
 * with no way back but a reinstall.
 */
@Singleton
internal class SecurityDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SecurityStore {

    override val preferences: Flow<SecurityPreferences> = context.securityDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> prefs.toSecurity() }

    override suspend fun setAppLock(enabled: Boolean) = edit { it[APP_LOCK] = enabled }

    override suspend fun setLockAfter(timeout: LockTimeout) = edit { it[LOCK_AFTER] = timeout.name }

    override suspend fun setBlockScreenshots(enabled: Boolean) = edit { it[BLOCK_SCREENSHOTS] = enabled }

    override suspend fun setHideAmounts(enabled: Boolean) = edit { it[HIDE_AMOUNTS] = enabled }

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        try {
            context.securityDataStore.edit(block)
        } catch (_: IOException) {
            // Not kept; the switch shows what was read.
        }
    }

    internal companion object {
        /** ADR-0030's permitted surface; `DatastoreKeySurfaceTest` holds the list. */
        internal val APP_LOCK = booleanPreferencesKey("security_app_lock")
        internal val LOCK_AFTER = stringPreferencesKey("security_lock_after")
        internal val BLOCK_SCREENSHOTS = booleanPreferencesKey("security_block_screenshots")
        internal val HIDE_AMOUNTS = booleanPreferencesKey("security_hide_amounts")
    }
}

internal fun Preferences.toSecurity(): SecurityPreferences {
    val defaults = SecurityPreferences()
    return SecurityPreferences(
        appLock = this[SecurityDataStore.APP_LOCK] ?: defaults.appLock,
        lockAfter = parseOr(this[SecurityDataStore.LOCK_AFTER], defaults.lockAfter),
        blockScreenshots = this[SecurityDataStore.BLOCK_SCREENSHOTS] ?: defaults.blockScreenshots,
        hideAmounts = this[SecurityDataStore.HIDE_AMOUNTS] ?: defaults.hideAmounts,
    )
}
