package com.ledgerflow.feature.settings.permissions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.ledgerflow.core.domain.security.AppPermissions
import com.ledgerflow.core.domain.security.HeldPermission
import com.ledgerflow.core.domain.security.PermissionGrant
import com.ledgerflow.core.domain.security.PermissionKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row of Data permissions, as the screen draws it. */
@Immutable
public data class PermissionRow(
    val kind: PermissionKind,
    val title: String,
    val sentence: String,
    /** Tapping opens Android's page for it; false for what Android grants at install. */
    val opensSettings: Boolean,
)

/** Data permissions' state: null until the package has been read. */
@Immutable
public data class DataPermissionsUiState(val rows: List<PermissionRow>? = null) {
    public val revocable: List<PermissionRow> get() = rows.orEmpty().filter { it.opensSettings }
    public val atInstall: List<PermissionRow> get() = rows.orEmpty().filterNot { it.opensSettings }
}

/**
 * Settings > Security & privacy > Data permissions (V2-PLAN §3.5).
 *
 * Read on every resume, like capture health: the user changes these in
 * Android's settings, and the row must say what is true when they come back.
 */
@HiltViewModel
public class DataPermissionsViewModel @Inject constructor(
    private val permissions: AppPermissions,
) : ViewModel() {

    private val _state = MutableStateFlow(DataPermissionsUiState())
    public val state: StateFlow<DataPermissionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        _state.value = DataPermissionsUiState(rows = permissionRows(permissions.held()))
    }
}

/** The rows for what the package holds, in its order. */
internal fun permissionRows(held: List<HeldPermission>): List<PermissionRow> = held.map { permission ->
    val why = permissionWhy(permission.kind)
    PermissionRow(
        kind = permission.kind,
        title = if (permission.kind == PermissionKind.UNEXPLAINED) {
            permission.androidNames.joinToString()
        } else {
            permissionTitle(permission.kind)
        },
        sentence = when (permission.grant) {
            PermissionGrant.ALLOWED -> "Allowed. $why"
            PermissionGrant.NOT_ALLOWED -> "Not allowed. $why"
            // The section heading already says so; the sentence is the reason.
            PermissionGrant.AT_INSTALL -> why
        },
        opensSettings = permission.kind.revocable,
    )
}
