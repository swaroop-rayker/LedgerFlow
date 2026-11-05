package com.ledgerflow.core.testing.security

import com.ledgerflow.core.domain.security.HideAmountsSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Hide amounts on Home in memory. [vault] null is a closed vault: the real
 * setting emits nothing then, and so does this.
 */
public class FakeHideAmountsSetting(initial: Boolean? = false) : HideAmountsSetting {

    public val vault: MutableStateFlow<Boolean?> = MutableStateFlow(initial)

    override val enabled: Flow<Boolean> = vault.filterNotNull()

    override suspend fun set(enabled: Boolean) {
        vault.value = enabled
    }
}
