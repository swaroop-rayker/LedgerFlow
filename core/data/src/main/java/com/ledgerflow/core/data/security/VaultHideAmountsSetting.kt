package com.ledgerflow.core.data.security

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.security.HideAmountsSetting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hide amounts on Home, as `app_meta.hideAmountsOnHome` (V2-PLAN §3.5).
 *
 * Off is the key's absence, not `"false"`: a vault that never touched the
 * switch reads exactly as it did before the switch existed, and so does an
 * older backup restored onto this build.
 */
@Singleton
internal class VaultHideAmountsSetting @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : HideAmountsSetting {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val enabled: Flow<Boolean> = session.whenUnlocked().flatMapLatest { database ->
        // Closed: say nothing, so Home keeps its figures hidden rather than
        // guessing "off" (DashboardUiState.amountsHidden).
        database?.appMetaDao()?.observeValue(KEY)?.map { it == ON } ?: emptyFlow()
    }

    override suspend fun set(enabled: Boolean): Unit = withContext(io) {
        val dao = session.requireDatabase().appMetaDao()
        if (enabled) dao.put(AppMetaEntity(KEY, ON)) else dao.remove(KEY)
    }

    private companion object {
        const val KEY = AppMetaEntity.KEY_HIDE_AMOUNTS_ON_HOME
        const val ON = "true"
    }
}
