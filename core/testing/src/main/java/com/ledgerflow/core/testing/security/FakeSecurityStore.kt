package com.ledgerflow.core.testing.security

import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.domain.security.SecurityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Settings > Security & privacy held in memory (ADR-0030's store, without the file). */
public class FakeSecurityStore(initial: SecurityPreferences = SecurityPreferences()) : SecurityStore {

    override val preferences: MutableStateFlow<SecurityPreferences> = MutableStateFlow(initial)

    override suspend fun setAppLock(enabled: Boolean) {
        preferences.update { it.copy(appLock = enabled) }
    }

    override suspend fun setLockAfter(timeout: LockTimeout) {
        preferences.update { it.copy(lockAfter = timeout) }
    }

    override suspend fun setBlockScreenshots(enabled: Boolean) {
        preferences.update { it.copy(blockScreenshots = enabled) }
    }

    override suspend fun setHideAmounts(enabled: Boolean) {
        preferences.update { it.copy(hideAmounts = enabled) }
    }
}
