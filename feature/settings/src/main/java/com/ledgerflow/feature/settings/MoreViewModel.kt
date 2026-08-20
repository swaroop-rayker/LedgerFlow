package com.ledgerflow.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
)

@HiltViewModel
public class MoreViewModel @Inject constructor(
    ledger: LedgerRepository,
) : ViewModel() {

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
    ) { debits, credits ->
        MoreUiState(deletedCount = debits + credits, isLoaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MoreUiState())

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
