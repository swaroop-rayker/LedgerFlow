package com.ledgerflow.applock

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgerflow.core.domain.security.DeviceSecurity
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.domain.security.SecurityStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app lock, for the whole process (SPEC §7.6, V2-PLAN §3.5).
 *
 * **A screen gate, nothing more.** It decides whether `MainActivity` draws the
 * lock over everything. It does not touch the vault, the DEK or the Keystore;
 * a capture worker, a notification action and the nightly backup run exactly
 * as they did, locked or not -- they never had a screen to gate.
 *
 * "Away" is the *process* going to the background (`ProcessLifecycleOwner`),
 * not an activity stopping, so rotating the phone or a system dialog over the
 * app is never "away". [AppLockState] holds the rules.
 *
 * [locked] and [preferences] are null until the security file has been read;
 * the activity draws nothing until then rather than flashing content it might
 * be about to cover.
 */
@Singleton
internal class AppLockController @Inject constructor(
    private val store: SecurityStore,
    private val device: DeviceSecurity,
) {
    private val state = AppLockState()

    private val _preferences = MutableStateFlow<SecurityPreferences?>(null)
    val preferences: StateFlow<SecurityPreferences?> = _preferences.asStateFlow()

    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    /** Called once, from `LedgerFlowApplication`, with a main-thread scope. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            store.preferences.collect { prefs ->
                _preferences.value = prefs
                state.preferencesChanged(prefs)
                publish()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    _preferences.value?.let { state.foregrounded(it, SystemClock.elapsedRealtime()) }
                    publish()
                }

                override fun onStop(owner: LifecycleOwner) {
                    state.backgrounded(SystemClock.elapsedRealtime())
                }
            },
        )
    }

    /** True while the phone's prompt is up, so a second tap does not open a second. */
    val isAuthenticating: Boolean get() = state.authenticating

    fun promptShown() {
        state.promptShown()
    }

    fun unlocked() {
        state.unlocked()
        publish()
    }

    fun promptClosed() {
        state.promptClosed()
        publish()
    }

    private fun publish() {
        val prefs = _preferences.value ?: return
        _locked.value = state.isLocked(prefs, device.hasScreenLock())
    }
}
