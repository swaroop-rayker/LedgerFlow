package com.ledgerflow.appearance

import com.ledgerflow.core.designsystem.format.DisplayConventions
import com.ledgerflow.core.domain.region.RegionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps [DisplayConventions] equal to Settings > Currency & region for the
 * whole process (V2-PLAN §3.3).
 *
 * Started by `LedgerFlowApplication`, not by the activity: a budget alert
 * posted by a worker with no Activity alive writes an amount too, and it must
 * group it the way the user chose. [start] takes a main-thread scope, because
 * [DisplayConventions] is snapshot state that composition reads.
 *
 * [ready] turns true once the first value has been written, and the activity
 * draws nothing until it has -- the same wait as the appearance read, so the
 * first amount on screen is already in the chosen form.
 */
@Singleton
internal class RegionConventionsSync @Inject constructor(
    private val store: RegionStore,
) {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            store.conventions.collect { conventions ->
                DisplayConventions.current = conventions
                _ready.value = true
            }
        }
    }
}
