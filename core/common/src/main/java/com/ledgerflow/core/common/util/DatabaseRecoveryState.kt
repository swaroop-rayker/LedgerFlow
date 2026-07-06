package com.ledgerflow.core.common.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DatabaseRecoveryState {
    data class IncompatibilityInfo(
        val currentVersion: Int,
        val expectedVersion: Int,
        val reason: String
    )

    private val _incompatibilityInfo = MutableStateFlow<IncompatibilityInfo?>(null)
    val incompatibilityInfo: StateFlow<IncompatibilityInfo?> = _incompatibilityInfo.asStateFlow()

    fun setIncompatible(currentVersion: Int, expectedVersion: Int, reason: String) {
        _incompatibilityInfo.value = IncompatibilityInfo(currentVersion, expectedVersion, reason)
    }

    fun clear() {
        _incompatibilityInfo.value = null
    }

    val isIncompatible: Boolean
        get() = _incompatibilityInfo.value != null
}
