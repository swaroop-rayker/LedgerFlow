package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.NotificationSetupStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The file itself.
 *
 * `preferencesDataStore` places it at `filesDir/datastore/listener_health.preferences_pb`,
 * which satisfies Law 5 — persistent data lives in `filesDir`, never `cacheDir`.
 * A property delegate on `Context` rather than a constructed instance because
 * DataStore requires exactly one instance per file per process, and the delegate
 * is the library's own way of guaranteeing that; two instances over one file
 * throw at run time rather than merging.
 */
internal val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listener_health",
)

/**
 * §5.2's listener liveness, persisted outside the vault (ADR-0020).
 *
 * ## What is on disk, and what is not
 *
 * Three timestamps. No amounts, no merchants, no message bodies, no package
 * names — nothing §5.2's privacy rule governs and nothing D-09's retention
 * window bounds. `DatastoreKeySurfaceTest` fails the build if a fourth key
 * appears without ADR-0020 being amended, because "this file holds nothing
 * sensitive" is a promise that decays the moment it is only a comment.
 *
 * [ListenerHealthRecord.connected] is **not** among them, and its absence is
 * load-bearing rather than an oversight — see that property's KDoc. It lives in
 * [connected], a process-scoped flag that starts `false` on every launch, which
 * is the truth: a listener in a dead process is not listening. A persisted flag
 * would say "connected" forever after the one event it exists to detect.
 *
 * ## Why the writes cannot fail loudly
 *
 * Every writer is `NotificationIngestService`, called by the system with no
 * Activity alive and often at boot. There is nothing to show an error to, and
 * an exception thrown out of `onListenerConnected` would take the listener down
 * with it. `IOException` is therefore absorbed on both directions — an
 * unreadable file reads as an empty record, which evaluates to
 * `RECONNECTING` and says nothing, rather than as a six-hour outage the user
 * does not have.
 */
@Singleton
internal class ListenerHealthDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ListenerHealthStore {

    /**
     * Whether this process holds a bound listener.
     *
     * A `MutableStateFlow` rather than a `@Volatile Boolean` so [record] can
     * emit when it flips; the Dashboard's banner is allowed to clear itself the
     * moment a rebind succeeds, without waiting for the next resume.
     */
    private val connected = MutableStateFlow(false)

    private val preferences: Flow<Preferences>
        get() = context.notificationDataStore.data.catch { cause ->
            // The documented recovery for a corrupt or unreadable preferences
            // file. Anything else is a programming error and is rethrown --
            // swallowing it here is how BUG13 read as success.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }

    override val record: Flow<ListenerHealthRecord> =
        combine(connected, preferences) { isConnected, prefs -> prefs.toRecord(isConnected) }

    override suspend fun current(): ListenerHealthRecord =
        preferences.map { it.toRecord(connected.value) }.first()

    override suspend fun recordConnected(atMillis: Long) {
        // The flag first, and deliberately: it is the half that matters for
        // liveness, it cannot fail, and the disk write below is the half that
        // may be interrupted by the process ending.
        connected.value = true
        edit { it[LAST_CONNECTED_AT] = atMillis }
    }

    override suspend fun recordDisconnected(atMillis: Long) {
        connected.value = false
        edit { it[LAST_DISCONNECTED_AT] = atMillis }
    }

    override suspend fun recordGrantObserved(atMillis: Long) {
        edit { preferences ->
            // First observation wins. Re-stamping on every poll would reset the
            // interval this value exists to measure, so a listener that never
            // binds would read as freshly granted forever.
            if (preferences[GRANT_OBSERVED_AT] == null) preferences[GRANT_OBSERVED_AT] = atMillis
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        // See the class KDoc: there is no surface to report this on and no
        // caller that could act on it. A lost timestamp degrades the banner's
        // precision; a thrown exception takes down the listener.
        try {
            context.notificationDataStore.edit(block)
        } catch (_: IOException) {
            // Intentionally ignored.
        }
    }

    private fun Preferences.toRecord(isConnected: Boolean) = ListenerHealthRecord(
        connected = isConnected,
        lastConnectedAt = this[LAST_CONNECTED_AT],
        lastDisconnectedAt = this[LAST_DISCONNECTED_AT],
        grantObservedAt = this[GRANT_OBSERVED_AT],
    )

    internal companion object {

        /**
         * The complete key surface, and ADR-0020's permitted list.
         *
         * `DatastoreKeySurfaceTest` reads the *sources* of this module for key
         * declarations and compares them against its own written-out list, so
         * adding a key here without amending that test and the ADR fails the
         * build. Naming them in one companion is the convenience; the guard is
         * what makes the promise hold.
         */
        internal val LAST_CONNECTED_AT = longPreferencesKey("listener_last_connected_at")
        internal val LAST_DISCONNECTED_AT = longPreferencesKey("listener_last_disconnected_at")
        internal val GRANT_OBSERVED_AT = longPreferencesKey("listener_grant_observed_at")
    }
}

/**
 * §5.2's first-run explainer, remembered (ADR-0020).
 *
 * The same file as [ListenerHealthDataStore] and a separate class, because the
 * two ports have separate callers — the service writes liveness, the UI writes
 * this — and sharing a *file* costs nothing while sharing an *interface* would
 * hand each caller the other's methods.
 *
 * `IOException` is absorbed on both directions, as it is above, and the
 * direction it fails in is the safe one: an unreadable file reads as "not yet
 * seen", so the worst outcome is the explainer appearing a second time. The
 * opposite default would silently swallow the only screen that grants
 * notification access.
 */
@Singleton
internal class NotificationSetupDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NotificationSetupStore {

    override suspend fun hasSeenSetup(): Boolean = try {
        context.notificationDataStore.data.first()[SETUP_SEEN] == true
    } catch (_: IOException) {
        false
    }

    override suspend fun markSetupSeen() {
        try {
            context.notificationDataStore.edit { it[SETUP_SEEN] = true }
        } catch (_: IOException) {
            // Nothing to report it on, and the cost is one repeated screen.
        }
    }

    internal companion object {
        internal val SETUP_SEEN = booleanPreferencesKey("notification_setup_seen")
    }
}
