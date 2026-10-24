package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.analytics.OverspendAnnouncements
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/** `filesDir/datastore/budget_alerts.preferences_pb` (Law 5). */
internal val Context.budgetAlertsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "budget_alerts",
)

/**
 * Which budgets have had their overspending warning this period (V2-PLAN §3.4).
 *
 * Here and not in the vault by the owner's choice (2026-10-24): a column on
 * `budget` would be a schema change for a once-per-period flag. ADR-0030 records
 * it as a named exception -- notification bookkeeping, not a preference --
 * because it does not need to be read before the vault opens. What it holds is
 * opaque budget ids with the day their period began ("id@19650"): no amount, no
 * category, no name. One entry per budget; recording a new period drops the
 * old one, so the set stays the size of the budget list.
 *
 * A write failure is swallowed: the worst outcome is one repeated warning.
 */
@Singleton
internal class OverspendAnnouncementsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OverspendAnnouncements {

    override suspend fun wasAnnounced(budgetId: String, periodStart: Int): Boolean =
        announcementEntry(budgetId, periodStart) in announced()

    override suspend fun record(budgetId: String, periodStart: Int) {
        try {
            context.budgetAlertsDataStore.edit { prefs ->
                prefs[ANNOUNCED] = prefs[ANNOUNCED].orEmpty().withAnnouncement(budgetId, periodStart)
            }
        } catch (_: IOException) {
            // Intentionally ignored; see the class KDoc.
        }
    }

    private suspend fun announced(): Set<String> = context.budgetAlertsDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .first()[ANNOUNCED]
        .orEmpty()

    internal companion object {
        /** ADR-0030's named exception; `DatastoreKeySurfaceTest` holds the list. */
        internal val ANNOUNCED = stringSetPreferencesKey("budget_overspend_announced")
    }
}

/** "id@periodStart": one budget's warning for one period. */
internal fun announcementEntry(budgetId: String, periodStart: Int): String = "$budgetId@$periodStart"

/** This set with [budgetId] warned for [periodStart], and no older period of it kept. */
internal fun Set<String>.withAnnouncement(budgetId: String, periodStart: Int): Set<String> =
    filterNot { it.startsWith("$budgetId@") }.toSet() + announcementEntry(budgetId, periodStart)
