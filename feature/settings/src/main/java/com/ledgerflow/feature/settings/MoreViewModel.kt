package com.ledgerflow.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

@HiltViewModel
public class MoreViewModel @Inject constructor(
    ledger: LedgerRepository,
    private val getCaptureHealth: GetNotificationCaptureHealthUseCase,
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
    public val state: StateFlow<MoreUiState> = combine(
        ledger.observeDeletedCount(LedgerType.DEBIT),
        ledger.observeDeletedCount(LedgerType.CREDIT),
        captureHealth,
    ) { debits, credits, health ->
        MoreUiState(
            deletedCount = debits + credits,
            isLoaded = true,
            captureHealth = health,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MoreUiState())

    init {
        refresh()
    }

    /** §5.2's resume poll. Called from the screen, for the reason its route explains. */
    public fun refresh() {
        viewModelScope.launch { captureHealth.value = getCaptureHealth() }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
