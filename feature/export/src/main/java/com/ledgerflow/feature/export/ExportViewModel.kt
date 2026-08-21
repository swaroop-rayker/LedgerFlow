package com.ledgerflow.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.export.ExportResult
import com.ledgerflow.core.domain.usecase.ExportCsvUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Export (SPEC.md §5.9, ADR-0017).
 *
 * The flow is deliberately three steps rather than one: **tap, confirm, pick.**
 * Collapsing the confirmation into the picker was tempting -- the picker is
 * already a deliberate act -- but the thing the user needs to know is not
 * *where* the file goes, it is *what the file is*: a complete, unencrypted copy
 * of their financial history. The system picker cannot say that, and by the time
 * it appears the decision has effectively been made.
 */
@HiltViewModel
public class ExportViewModel @Inject constructor(
    private val exportCsv: ExportCsvUseCase,
) : ViewModel() {

    private val internalState = MutableStateFlow(ExportUiState())
    public val state: StateFlow<ExportUiState> = internalState.asStateFlow()

    /** The name offered in the picker, so the screen can show it beforehand. */
    public val suggestedFileName: String get() = exportCsv.suggestedFileName()

    public fun onEvent(event: ExportEvent) {
        when (event) {
            ExportEvent.ExportRequested ->
                internalState.update { it.copy(confirming = true, status = ExportStatus.Idle) }

            ExportEvent.WarningAccepted ->
                internalState.update { it.copy(confirming = false, pickerRequest = true) }

            ExportEvent.WarningDismissed ->
                internalState.update { it.copy(confirming = false) }

            ExportEvent.PickerLaunched ->
                internalState.update { it.copy(pickerRequest = false) }

            is ExportEvent.DestinationChosen -> onDestinationChosen(event.uri)

            ExportEvent.StatusDismissed ->
                internalState.update { it.copy(status = ExportStatus.Idle) }
        }
    }

    /**
     * A null URI means the user backed out of the picker.
     *
     * Silence is the right response: they cancelled, and reporting "export
     * failed" for a deliberate cancellation is how a screen teaches people to
     * ignore its messages.
     */
    private fun onDestinationChosen(uri: String?) {
        if (uri == null) return

        viewModelScope.launch {
            internalState.update { it.copy(status = ExportStatus.Working) }
            val result = exportCsv(uri)
            internalState.update { it.copy(status = result.toStatus()) }
        }
    }
}

/**
 * Every outcome as something the user can act on.
 *
 * The technical reason never reaches the screen. A `SecurityException` from a
 * revoked SAF grant and a full disk are the same sentence to the person holding
 * the phone -- "it did not save, choose somewhere else" -- and printing the
 * exception text instead would be the app admitting it does not know what
 * happened.
 */
private fun ExportResult.toStatus(): ExportStatus = when (this) {
    is ExportResult.Success -> ExportStatus.Done(fileCount, rowCount)
    ExportResult.VaultLocked ->
        ExportStatus.Failed("Your vault is locked. Unlock it and try again.")
    is ExportResult.Failure ->
        ExportStatus.Failed("That location could not be written to. Try somewhere else.")
}
