package com.ledgerflow.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the More tab asks its ViewModel to do. */
public sealed interface MoreEvent {

    /** Opens the confirmation. Does not delete anything by itself. */
    public data object ReceiptDeleteRequested : MoreEvent

    /** Irreversible (ADR-0023). */
    public data object ReceiptDeleteConfirmed : MoreEvent

    public data object ReceiptDeleteDismissed : MoreEvent
}

/**
 * The More tab's state (SPEC.md §9.3).
 *
 * It had no ViewModel until the bin arrived, because until then every row on it
 * was a static label over a navigation callback. This one still is a navigation
 * callback — but its subtitle has to say how much is in the bin, and that is a
 * question only the database can answer.
 *
 * Everything that *acts* on the bin lives in the bin (ADR-0015). This screen
 * counts and points.
 */
@Immutable
public data class MoreUiState(
    /**
     * Deleted entries across **both** books.
     *
     * Summed for display only. Nothing is netted and nothing is compared — a
     * count of rows is not a monetary figure, so Law 2 has no opinion about it,
     * and the underlying queries are still one per book.
     */
    val deletedCount: Int = 0,

    /**
     * Whether the count has been read yet.
     *
     * Zero and "not asked" would otherwise render the same subtitle, and one of
     * them is a lie for the fraction of a second before the query returns.
     */
    val isLoaded: Boolean = false,

    /**
     * Receipt images on disk (ADR-0023).
     *
     * The ADR declines a timed purge and makes growth **visible** instead, so
     * this row is the whole of that decision's user-facing half — and, until
     * a viewer exists, the only way to remove an image at all.
     */
    val receipts: AttachmentUsage = AttachmentUsage(count = 0, bytes = 0L),

    /** True while the irreversible confirmation is up. */
    val confirmingReceiptDelete: Boolean = false,

    /**
     * Whether notification capture is working (SPEC.md §5.2).
     *
     * The row it drives is the standing route to the permission explainer, and
     * the reason it carries a status rather than a static label is the same
     * lesson [deletedSubtitle] records one row up: a Settings row that says
     * nothing about its own state is one people open to find out, and a user who
     * suspects capture is broken looks here first.
     *
     * Polled on resume like every other reader of this value — the grant lives
     * in system Settings and changes without telling anyone.
     */
    val captureHealth: NotificationCaptureHealth = NotificationCaptureHealth.RECONNECTING,

    /**
     * When the last **verified** backup was written, or null if never
     * (§16 Q23). Only a backup that passed its read-back check records this,
     * so the row never reassures on the strength of a failed one.
     */
    val lastBackupAt: Long? = null,
)

@HiltViewModel
public class MoreViewModel @Inject constructor(
    ledger: LedgerRepository,
    private val getCaptureHealth: GetNotificationCaptureHealthUseCase,
    private val attachments: AttachmentRepository,
    backups: BackupRepository,
) : ViewModel() {

    /**
     * The polled half.
     *
     * A `MutableStateFlow` folded into the combine below rather than a second
     * `StateFlow` the screen also collects: one screen gets one state object
     * (CLAUDE.md §5), and two flows would let the row's label and its subtitle
     * recompose out of step.
     */
    private val captureHealth =
        MutableStateFlow(NotificationCaptureHealth.RECONNECTING)

    /**
     * One flow per book, combined here rather than in a query.
     *
     * There is deliberately no count that answers for both: a statement
     * spanning the two books is the shape ADR-0002 removes, and a screen adding
     * two numbers together is not the same thing as a database doing it.
     */
    /**
     * Receipt storage, polled rather than observed.
     *
     * It is a measurement of the *filesystem*, not a query, so there is no
     * `Flow` to collect — and it changes only when the user scans or deletes,
     * both of which already pass through [refresh] or [onEvent].
     */
    private val receipts = MutableStateFlow(AttachmentUsage(count = 0, bytes = 0L))

    private val confirmingDelete = MutableStateFlow(false)

    public val state: StateFlow<MoreUiState> = combine(
        ledger.observeDeletedCount(LedgerType.DEBIT),
        ledger.observeDeletedCount(LedgerType.CREDIT),
        captureHealth,
        receipts,
        confirmingDelete,
    ) { debits, credits, health, images, confirming ->
        MoreUiState(
            deletedCount = debits + credits,
            isLoaded = true,
            captureHealth = health,
            receipts = images,
            confirmingReceiptDelete = confirming,
        )
    }
        // Joined second rather than as a sixth argument: `combine` is typed
        // to five, and the array form would give up the types.
        .combine(backups.lastBackupAt()) { state, last -> state.copy(lastBackupAt = last) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MoreUiState())

    init {
        refresh()
    }

    /** §5.2's resume poll. Called from the screen, for the reason its route explains. */
    public fun refresh() {
        viewModelScope.launch {
            captureHealth.value = getCaptureHealth()
            receipts.value = attachments.usage()
        }
    }

    public fun onEvent(event: MoreEvent) {
        when (event) {
            // The row opens the confirmation rather than doing anything, even
            // at zero: a destructive control that is sometimes inert teaches
            // people to tap it without reading.
            MoreEvent.ReceiptDeleteRequested -> confirmingDelete.value = true

            MoreEvent.ReceiptDeleteDismissed -> confirmingDelete.value = false

            MoreEvent.ReceiptDeleteConfirmed -> viewModelScope.launch {
                attachments.deleteAll()
                confirmingDelete.value = false
                // Re-measured rather than assumed to be zero: a failed unlink
                // must show as a file still there, not as a clean slate the
                // app merely hoped for.
                receipts.value = attachments.usage()
            }
        }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
