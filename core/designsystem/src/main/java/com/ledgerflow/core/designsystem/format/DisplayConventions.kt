package com.ledgerflow.core.designsystem.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ledgerflow.core.model.RegionConventions

/**
 * Settings > Currency & region, as every amount and date on screen reads it
 * (`docs/V2-PLAN.md` §3.3).
 *
 * **Process-wide, like `Locale.getDefault()`, and for the same reason.**
 * [MoneyFormat] and [DisplayDates] are called from composables, ViewModels and
 * a notification posted by a worker with no Activity alive; threading the
 * choice through every one of those signatures would touch every amount in the
 * app for a setting changed once. `LedgerFlowApplication` keeps this in step
 * with the store for the whole process, and `MainActivity` sets it from the
 * same read it waits on before the first frame, so nothing draws with a stale
 * value.
 *
 * **Snapshot state, not a plain field.** A composable that formats an amount
 * while it composes reads this through the formatter, Compose records the
 * read, and changing the setting redraws it -- the Settings page's own sample,
 * and any list on screen. A string a ViewModel formatted earlier changes when
 * that ViewModel next emits.
 *
 * Tests that change it put it back (`try`/`finally`): it is global.
 */
public object DisplayConventions {

    public var current: RegionConventions by mutableStateOf(RegionConventions())
}
