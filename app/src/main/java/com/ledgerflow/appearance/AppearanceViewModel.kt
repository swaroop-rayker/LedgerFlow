package com.ledgerflow.appearance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.AppearanceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Settings > Appearance, as the activity applies it (V2-PLAN §3.2).
 *
 * Separate from `AppViewModel`, which routes on the vault: these choices apply
 * before the vault opens and whatever it does.
 *
 * **Null until the file has been read**, and the activity draws nothing until
 * then rather than guessing. The read takes a few milliseconds and the launch
 * window is already showing, so waiting costs a frame of that window; guessing
 * "the phone's theme" would flash the wrong one at every user who chose the
 * other. Eager, so the read starts with the activity rather than with the
 * first collector.
 */
@HiltViewModel
public class AppearanceViewModel @Inject constructor(
    store: AppearanceStore,
) : ViewModel() {

    public val preferences: StateFlow<AppearancePreferences?> =
        store.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
