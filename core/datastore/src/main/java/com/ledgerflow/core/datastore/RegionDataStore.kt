package com.ledgerflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.domain.region.RegionStore
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** `filesDir/datastore/region.preferences_pb` (Law 5). One file per Settings page. */
internal val Context.regionDataStore: DataStore<Preferences> by preferencesDataStore(name = "region")

/**
 * Settings > Currency & region, outside the vault (`docs/V2-PLAN.md` §3.3,
 * ADR-0030).
 *
 * Two enum names, admitted on ADR-0030's test: worth nothing to a thief,
 * needed before the vault opens (the unlock screen and a budget notification
 * posted with no Activity alive both write amounts), and device-local -- a
 * `.lfbk` does not carry them. Read leniently, for the reason
 * [AppearanceDataStore] gives: this read decides how the first amount is drawn.
 */
@Singleton
internal class RegionDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RegionStore {

    override val conventions: Flow<RegionConventions> = context.regionDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> prefs.toRegionConventions() }

    override suspend fun setGrouping(grouping: NumberGrouping) = write(GROUPING, grouping.name)

    override suspend fun setDateOrder(order: DateOrder) = write(DATE_ORDER, order.name)

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        try {
            context.regionDataStore.edit { it[key] = value }
        } catch (_: IOException) {
            // Not kept; the row keeps showing what was read.
        }
    }

    internal companion object {
        /** ADR-0030's permitted surface; `DatastoreKeySurfaceTest` holds the list. */
        internal val GROUPING = stringPreferencesKey("region_number_grouping")
        internal val DATE_ORDER = stringPreferencesKey("region_date_order")
    }
}

internal fun Preferences.toRegionConventions(): RegionConventions {
    val defaults = RegionConventions()
    return RegionConventions(
        grouping = parseOr(this[RegionDataStore.GROUPING], defaults.grouping),
        dateOrder = parseOr(this[RegionDataStore.DATE_ORDER], defaults.dateOrder),
    )
}
