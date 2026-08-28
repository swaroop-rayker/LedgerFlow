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
import com.ledgerflow.core.domain.usecase.PurgePendingUseCase
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
    private val purgePending: PurgePendingUseCase,
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
            // Ticks for rows that are no longer listed are dropped rather than
            // carried: a purge, a restore or a re-triage can remove a row under
            // the user, and a stale id would make "Erase 3" name a count that
            // does not match what is on screen.
            selected = extra.selected intersect rows.map { it.id }.toSet(),
            isWorking = extra.isWorking,
            confirmation = extra.confirmation,
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
            is InboxEvent.FilterSelected -> {
                filter.value = event.filter
                // A selection that survived a tab switch would let "Erase 3"
                // mean three rows the user can no longer see.
                transient.update { it.copy(selected = emptySet(), confirmation = null) }
            }

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

            // ── Erasing (CHANGE#1) ──────────────────────────────────────────

            is InboxEvent.SelectionToggled -> transient.update { current ->
                val next = if (event.pendingId in current.selected) {
                    current.selected - event.pendingId
                } else {
                    current.selected + event.pendingId
                }
                current.copy(selected = next)
            }

            InboxEvent.SelectionCleared ->
                transient.update { it.copy(selected = emptySet()) }

            // Both of these only OPEN the dialog. Nothing here destroys a row --
            // the delete is reachable from `EraseConfirmed` alone, so no tap on
            // a list control can get to it without the warning in between.
            InboxEvent.EraseSelectedRequested -> {
                // From `transient`, not from `state`: the assembled state is a
                // `WhileSubscribed` flow and reads as empty when nothing is
                // collecting. The screen always is, so this is belt-and-braces
                // rather than a live bug -- but a count that silently reads zero
                // is exactly the kind of thing that makes a destructive control
                // do nothing and look broken.
                val count = transient.value.selected.size
                if (count > 0) {
                    transient.update {
                        it.copy(confirmation = InboxConfirmation.EraseSelected(count))
                    }
                }
            }

            InboxEvent.EraseAllRequested -> {
                val current = state.value
                if (current.canErase && current.rows.isNotEmpty()) {
                    transient.update {
                        it.copy(
                            confirmation = InboxConfirmation.EraseAll(
                                count = current.rows.size,
                                filter = current.filter,
                            ),
                        )
                    }
                }
            }

            InboxEvent.EraseDismissed ->
                transient.update { it.copy(confirmation = null) }

            InboxEvent.EraseConfirmed -> erase()
        }
    }

    /**
     * The irreversible one.
     *
     * Reads the confirmation rather than the current selection, so what is
     * destroyed is what the dialog named. Between the dialog opening and the
     * user confirming, a row can arrive or leave; erasing "whatever is selected
     * now" would destroy a set the user was never shown.
     */
    private fun erase() {
        val confirmation = transient.value.confirmation ?: return
        val ids = transient.value.selected.toList()
        transient.update { it.copy(confirmation = null, isWorking = true) }

        viewModelScope.launch {
            val erased = when (confirmation) {
                is InboxConfirmation.EraseSelected -> purgePending.selected(ids)
                is InboxConfirmation.EraseAll -> purgePending.all(confirmation.filter)
            }
            transient.update {
                it.copy(
                    selected = emptySet(),
                    isWorking = false,
                    // The count is what was actually erased, not what was asked
                    // for. The SQL refuses an approved candidate however it was
                    // selected, and saying "3 erased" over 2 would be the app
                    // reporting its own intention rather than the outcome.
                    message = when (erased) {
                        0 -> "Nothing was erased."
                        1 -> "1 item erased for good."
                        else -> "$erased items erased for good."
                    },
                )
            }
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
        val selected: Set<String> = emptySet(),
        val isWorking: Boolean = false,
        val confirmation: InboxConfirmation? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
