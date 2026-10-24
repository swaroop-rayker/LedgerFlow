package com.ledgerflow.core.domain.region

import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import kotlinx.coroutines.flow.Flow

/**
 * Where Settings > Currency & region keeps its choices: `:core:datastore`
 * (ADR-0030), outside the vault, because an amount or a date on the unlock and
 * Recovery screens, and in a budget notification posted with no Activity
 * alive, is written with them.
 *
 * The base currency is **not** here. It lives in the vault (`app_meta`, §5.8),
 * every stored amount is in it, and it does not change (D4).
 */
public interface RegionStore {

    /** The current choices; defaults (today's behaviour) until first written. */
    public val conventions: Flow<RegionConventions>

    public suspend fun setGrouping(grouping: NumberGrouping)

    public suspend fun setDateOrder(order: DateOrder)
}
