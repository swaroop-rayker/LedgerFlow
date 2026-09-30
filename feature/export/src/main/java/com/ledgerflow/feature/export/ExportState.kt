package com.ledgerflow.feature.export

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.export.ExportFormat

/**
 * The export screen (SPEC.md §5.9, ADR-0017).
 *
 * A small screen with one action, which is why the state is mostly about what
 * the user has been told rather than about what they have chosen: the whole
 * design problem here is that the artifact is **unencrypted**, and the file is
 * gone from the app's control the moment it is written.
 */
@Immutable
public data class ExportUiState(
    val status: ExportStatus = ExportStatus.Idle,

    /**
     * Whether the unencrypted-file warning is up.
     *
     * State rather than a `remember` in the composable, like every other dialog
     * in the app: a rotation mid-question would otherwise dismiss it, and the
     * question is the user's only protection on this screen.
     */
    val confirming: Boolean = false,

    /**
     * Set when the user has confirmed and the picker should open.
     *
     * Consumed by the screen the moment it launches, so a config change while
     * the system document picker is in front does not launch a second one
     * behind it -- the same shape `OnboardingScreen` uses for the Recovery Kit.
     */
    val pickerRequest: Boolean = false,

    /**
     * What the file will be. XLSX first (ADR-0004): it opens as a finished
     * spreadsheet with the summaries on top, which is what most people want;
     * CSV stays for anyone feeding another tool.
     */
    val format: ExportFormat = ExportFormat.XLSX,
)

/** Where the export has got to. */
public sealed interface ExportStatus {

    public data object Idle : ExportStatus

    /**
     * Writing. The whole database is being read and zipped, so on a real ledger
     * this is long enough to need saying.
     */
    public data object Working : ExportStatus

    /** @param fileCount CSV files in the zip, or sheets in the workbook, per [format]. */
    public data class Done(
        val fileCount: Int,
        val rowCount: Int,
        val format: ExportFormat = ExportFormat.CSV,
    ) : ExportStatus

    /** A sentence, already made readable by the ViewModel. */
    public data class Failed(val message: String) : ExportStatus
}

public sealed interface ExportEvent {

    /** The user tapped Export. Raises the warning; writes nothing. */
    public data object ExportRequested : ExportEvent

    /** Warning accepted. The picker opens next. */
    public data object WarningAccepted : ExportEvent

    public data object WarningDismissed : ExportEvent

    /** The screen has launched the picker, so the request must not fire twice. */
    public data object PickerLaunched : ExportEvent

    /** Null when the user backed out of the picker, which is not a failure. */
    public data class DestinationChosen(val uri: String?) : ExportEvent

    /** Clears a finished or failed run so the screen can be used again. */
    public data object StatusDismissed : ExportEvent

    /** The user picked Excel or CSV. Ignored while an export is running. */
    public data class FormatSelected(val format: ExportFormat) : ExportEvent
}
