package com.ledgerflow.core.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import org.junit.Test

/** How the region file reads back (ADR-0030): never throws, defaults are today's behaviour. */
class RegionConventionsParseTest {

    @Test
    fun anEmptyFile_isWhatTheAppAlwaysDid() {
        assertThat(emptyPreferences().toRegionConventions()).isEqualTo(
            RegionConventions(NumberGrouping.BY_CURRENCY, DateOrder.AUTO),
        )
    }

    @Test
    fun storedNames_readBack() {
        val prefs = mutablePreferencesOf(
            RegionDataStore.GROUPING to "WESTERN",
            RegionDataStore.DATE_ORDER to "YEAR_MONTH_DAY",
        )
        assertThat(prefs.toRegionConventions()).isEqualTo(
            RegionConventions(NumberGrouping.WESTERN, DateOrder.YEAR_MONTH_DAY),
        )
    }

    @Test
    fun aNameThisBuildDoesNotKnow_isTheDefault() {
        val prefs = mutablePreferencesOf(
            RegionDataStore.GROUPING to "SWISS",
            RegionDataStore.DATE_ORDER to "MONTH_DAY_YEAR",
        )
        assertThat(prefs.toRegionConventions()).isEqualTo(
            RegionConventions(NumberGrouping.BY_CURRENCY, DateOrder.MONTH_DAY_YEAR),
        )
    }
}
