package com.ledgerflow.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.inbox.InboxError
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.InboxException
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.domain.usecase.RestorePendingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Inbox (SPEC.md §5.1). P2-6.
 *
 * **Approving is the one thing here that reaches the ledger**, and it does so
 * through [ApprovePendingUseCase], which composes Law 1's single writer rather
 * than replacing it. Nothing in this class writes to `ledger_entry`.
 *
 * The list is a `Flow` per filter rather than a filtered snapshot: a message can
 * land while the user is looking at the screen, and the row should appear
 * without them going anywhere.
 */
@HiltViewModel
public class InboxViewModel @Inject constructor(
    observePending: ObservePendingUseCase,
    observePendingCount: ObservePendingCountUseCase,
    private val approvePending: ApprovePendingUseCase,
    private val discardPending: DiscardPendingUseCase,
    private val restorePending: RestorePendingUseCase,
) : ViewModel() {

    private val filter = MutableStateFlow(InboxFilter.PENDING)
    private val transient = MutableStateFlow(TransientState())

    @OptIn(ExperimentalCoroutinesApi::class)
    public val state: StateFlow<InboxUiState> = combine(
        filter,
        filter.flatMapLatest(observePending::invoke),
        observePendingCount(),
        transient,
    ) { selected, rows, count, extra ->
        InboxUiState(
            filter = selected,
            rows = rows,
            pendingCount = count,
            loading = false,
            undoableDiscard = extra.undoableDiscard,
            message = extra.message,
        )
    }.stateIn(
        scope = viewModelScope,
        // The Inbox's flows are database queries, and a rotation should not
        // re-run them. Long enough to survive a configuration change, short
        // enough that a backgrounded screen stops observing.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = InboxUiState(),
    )

    public fun onEvent(event: InboxEvent) {
        when (event) {
            is InboxEvent.FilterSelected -> filter.value = event.filter

            is InboxEvent.Approved -> viewModelScope.launch {
                approvePending(event.pendingId).fold(
                    onSuccess = { post("Added to your ledger.") },
                    onFailure = { post(it.asMessage()) },
                )
            }

            is InboxEvent.Discarded -> viewModelScope.launch {
                if (discardPending(event.pendingId)) {
                    // The row is already DISCARDED on disk. The snackbar offers
                    // to put it back rather than holding the write -- a deferred
                    // discard would be lost to a process death with the row
                    // still in the queue.
                    transient.update {
                        it.copy(undoableDiscard = UndoableDiscard(event.pendingId, event.label))
                    }
                }
            }

            is InboxEvent.Restored -> viewModelScope.launch {
                restorePending(event.pendingId)
            }

            InboxEvent.UndoDiscard -> {
                val pendingId = transient.value.undoableDiscard?.pendingId
                transient.update { it.copy(undoableDiscard = null) }
                if (pendingId != null) viewModelScope.launch { restorePending(pendingId) }
            }

            InboxEvent.UndoExpired -> transient.update { it.copy(undoableDiscard = null) }

            InboxEvent.MessageShown -> transient.update { it.copy(message = null) }
        }
    }

    private fun post(message: String) {
        transient.update { it.copy(message = message) }
    }

    /**
     * A refusal the user can act on, rather than a type name.
     *
     * `AlreadyReviewed` is the one that actually happens: a notification action
     * (§5.1) racing the screen, or a deep link tapped twice. Saying so beats a
     * generic failure, because the user's next question is whether their money
     * got recorded twice.
     */
    private fun Throwable.asMessage(): String = when (val error = (this as? InboxException)?.error) {
        is InboxError.AlreadyReviewed -> "Already ${error.status.name.lowercase()}."
        InboxError.NotFound -> "That item is no longer here."
        InboxError.NotReviewable -> "Needs an amount and a book first."
        is InboxError.Rejected -> "The ledger refused it: ${error.reason}"
        null -> "Could not add it."
    }

    private data class TransientState(
        val undoableDiscard: UndoableDiscard? = null,
        val message: String? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
