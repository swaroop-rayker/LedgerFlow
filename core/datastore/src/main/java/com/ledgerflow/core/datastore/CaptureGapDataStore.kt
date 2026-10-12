package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal val Context.captureGapDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_gap",
)

/**
 * The capture-gap window, outside the vault (SPEC.md §16 Q16, ADR-0020).
 *
 * Its own preferences file rather than a row in `listener_health`: a different
 * fact, and one the user clears, so it gets its own lifecycle. Three keys, all
 * in ADR-0020's permitted surface: two timestamps about the app's own capture
 * failing, and how many times it did. Never what was missed.
 *
 * Written from a `BroadcastReceiver` and a `NotificationListenerService` with
 * no Activity alive, so -- like [ListenerHealthDataStore] -- a write failure is
 * swallowed rather than thrown: an exception escaping here would take the
 * receiver with it and lose the next message too.
 */
@Singleton
internal class CaptureGapDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CaptureGapStore {

    override val gap: Flow<CaptureGap?> = context.captureGapDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            val first = prefs[FIRST_MISSED_AT] ?: return@map null
            CaptureGap(
                firstMissedAt = first,
                lastMissedAt = prefs[LAST_MISSED_AT] ?: first,
                missed = prefs[MISSED] ?: 1,
            )
        }

    override suspend fun recordMissed(atMillis: Long) {
        try {
            // One transaction, so two messages lost in the same instant (an SMS
            // and its notification copy) both count and neither resets the window.
            context.captureGapDataStore.edit { prefs ->
                if (prefs[FIRST_MISSED_AT] == null) prefs[FIRST_MISSED_AT] = atMillis
                prefs[LAST_MISSED_AT] = maxOf(atMillis, prefs[LAST_MISSED_AT] ?: atMillis)
                prefs[MISSED] = (prefs[MISSED] ?: 0) + 1
            }
        } catch (_: IOException) {
            // Intentionally ignored; see the class KDoc.
        }
    }

    override suspend fun clear() {
        try {
            context.captureGapDataStore.edit { it.clear() }
        } catch (_: IOException) {
            // The banner stays; the user can press "Got it" again.
        }
    }

    internal companion object {
        /** ADR-0020's permitted surface; `DatastoreKeySurfaceTest` holds the list. */
        internal val FIRST_MISSED_AT = longPreferencesKey("capture_gap_first_missed_at")
        internal val LAST_MISSED_AT = longPreferencesKey("capture_gap_last_missed_at")
        internal val MISSED = intPreferencesKey("capture_gap_missed")
    }
}
