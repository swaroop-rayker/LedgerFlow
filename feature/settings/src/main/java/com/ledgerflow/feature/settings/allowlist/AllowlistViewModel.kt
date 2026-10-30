package com.ledgerflow.feature.settings.allowlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.InstalledApp
import com.ledgerflow.core.domain.ingest.InstalledApps
import com.ledgerflow.core.domain.ingest.PackageAllowlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the allowlist editor asks of its ViewModel. */
public sealed interface AllowlistEvent {
    public data class Toggled(val packageName: String, val enabled: Boolean) : AllowlistEvent

    /** An app the user added; a built-in is switched off instead. */
    public data class Removed(val packageName: String) : AllowlistEvent

    /** Opens the installed-app list. */
    public data object AddRequested : AllowlistEvent

    public data object PickerClosed : AllowlistEvent

    /** Adds it -- or, for an app that carries personal messages, asks first. */
    public data class Picked(val app: InstalledApp) : AllowlistEvent

    public data object WarningConfirmed : AllowlistEvent

    public data object WarningDismissed : AllowlistEvent

    /** The picker's search field changed (owner, 2026-10-30). */
    public data class QueryChanged(val text: String) : AllowlistEvent
}

/** Settings > Notifications > Payment apps (SPEC §5.2, D-10; V2-PLAN §3.4). */
@Immutable
public data class AllowlistUiState(
    val apps: List<AllowlistedApp> = emptyList(),
    val isLoaded: Boolean = false,
    /** True while the installed-app list is shown in place of the allowlist. */
    val picking: Boolean = false,
    /**
     * Installed apps not already on the list, read when the picker opens,
     * narrowed to [query] by name or package name.
     */
    val installed: List<InstalledApp> = emptyList(),
    /** What the picker's search field holds; empty shows every app. */
    val query: String = "",
    /** True once the phone's apps have been read, so an empty [installed] means "no match". */
    val installedRead: Boolean = false,
    /** The app awaiting the personal-messages warning, if any. */
    val confirming: InstalledApp? = null,
)

@HiltViewModel
public class AllowlistViewModel @Inject constructor(
    private val allowlist: PackageAllowlistRepository,
    private val installedApps: InstalledApps,
) : ViewModel() {

    private val picking = MutableStateFlow(false)
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val confirming = MutableStateFlow<InstalledApp?>(null)
    private val query = MutableStateFlow("")

    public val state: StateFlow<AllowlistUiState> = combine(
        allowlist.observe(),
        picking,
        installed,
        confirming,
        query,
    ) { apps, isPicking, phoneApps, pending, text ->
        val listed = apps.map { it.packageName }.toSet()
        AllowlistUiState(
            apps = apps,
            isLoaded = true,
            picking = isPicking,
            installed = phoneApps.filterNot { it.packageName in listed }.filter { it.matches(text) },
            query = text,
            installedRead = phoneApps.isNotEmpty(),
            confirming = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AllowlistUiState())

    public fun onEvent(event: AllowlistEvent) {
        when (event) {
            is AllowlistEvent.Toggled ->
                viewModelScope.launch { allowlist.setEnabled(event.packageName, event.enabled) }

            is AllowlistEvent.Removed -> viewModelScope.launch { allowlist.remove(event.packageName) }

            AllowlistEvent.AddRequested -> {
                picking.value = true
                viewModelScope.launch { installed.value = installedApps.launchable() }
            }

            AllowlistEvent.PickerClosed -> {
                picking.value = false
                query.value = ""
            }

            is AllowlistEvent.QueryChanged -> query.value = event.text

            is AllowlistEvent.Picked ->
                if (event.app.carriesPersonalMessages) confirming.value = event.app else add(event.app)

            AllowlistEvent.WarningConfirmed -> confirming.value?.let(::add)

            AllowlistEvent.WarningDismissed -> confirming.value = null
        }
    }

    private fun add(app: InstalledApp) {
        confirming.value = null
        picking.value = false
        query.value = ""
        viewModelScope.launch { allowlist.add(app.packageName, app.label) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The search matches an app by its name or its package name, ignoring case and outer spaces. */
internal fun InstalledApp.matches(query: String): Boolean {
    val text = query.trim()
    return text.isEmpty() || label.contains(text, ignoreCase = true) || packageName.contains(text, ignoreCase = true)
}
