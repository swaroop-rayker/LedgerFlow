package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.AppearanceStore
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Its own file, apart from ADR-0020's operational ones: these are the user's
 * choices rather than facts about the app's machinery, and ADR-0030 admits them
 * on their own terms. `filesDir/datastore/appearance.preferences_pb` (Law 5).
 */
internal val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "appearance",
)

/**
 * Settings > Appearance, outside the vault (`docs/V2-PLAN.md` §3.2, ADR-0030).
 *
 * Outside because the theme paints the unlock screen and the start screen is
 * chosen the instant the vault opens: neither can wait for a database that may
 * not open at all. Nothing here is worth stealing -- three enum names -- which
 * is ADR-0030's test for being allowed here. **A `.lfbk` does not carry them**:
 * a restore onto a new phone starts on the defaults.
 *
 * Values are stored by name and read back leniently ([parseOr]): a name this
 * build does not know (a file written by a newer version, then downgraded)
 * reads as the default rather than failing the whole flow, because a theme
 * read that throws is a blank app.
 */
@Singleton
internal class AppearanceDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppearanceStore {

    override val preferences: Flow<AppearancePreferences> = context.appearanceDataStore.data
        // A corrupt or unreadable file is the defaults, not a crash: the app
        // must still draw. CorruptionException is an IOException.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> prefs.toAppearance() }

    override suspend fun setTheme(theme: ThemePreference) = write(THEME, theme.name)

    override suspend fun setAnimations(animations: AnimationPreference) =
        write(ANIMATIONS, animations.name)

    override suspend fun setStartScreen(startScreen: StartScreen) =
        write(START_SCREEN, startScreen.name)

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        try {
            context.appearanceDataStore.edit { it[key] = value }
        } catch (_: IOException) {
            // The choice is not kept; the control shows what was read, so the
            // user sees it did not take rather than being told it did.
        }
    }

    internal companion object {
        /** ADR-0030's permitted surface; `DatastoreKeySurfaceTest` holds the list. */
        internal val THEME = stringPreferencesKey("appearance_theme")
        internal val ANIMATIONS = stringPreferencesKey("appearance_animations")
        internal val START_SCREEN = stringPreferencesKey("appearance_start_screen")
    }
}

internal fun Preferences.toAppearance(): AppearancePreferences {
    val defaults = AppearancePreferences()
    return AppearancePreferences(
        theme = parseOr(this[AppearanceDataStore.THEME], defaults.theme),
        animations = parseOr(this[AppearanceDataStore.ANIMATIONS], defaults.animations),
        startScreen = parseOr(this[AppearanceDataStore.START_SCREEN], defaults.startScreen),
    )
}

/** [stored] as an [E], or [fallback] when it is absent or a name this build does not know. */
internal inline fun <reified E : Enum<E>> parseOr(stored: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: fallback
