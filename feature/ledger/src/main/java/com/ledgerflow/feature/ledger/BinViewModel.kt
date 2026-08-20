package com.ledgerflow.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.BinnedRef
import com.ledgerflow.core.domain.usecase.PurgeDeletedEntriesUseCase
import com.ledgerflow.core.domain.usecase.RestoreEntryUseCase
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The bin: everything deleted, from both books (SPEC.md §5.5, ADR-0015).
 *
 * **The one surface in the app that shows DEBIT and CREDIT together.** It reads
 * two flows, one per book — no statement spans them — and merges the results
 * here for display. Law 2 survives because nothing is ever summed: the merge
 * produces a *list*, every row keeps its own `ledger`, and the screen signs and
 * colours each independently. What Law 2 forbids is a combined figure, and
 * there is no figure here at all.
 *
 * Sorted by when each entry *happened*, not by when it was binned, because that
 * is the date the rows display — a list ordered by one date while showing
 * another reads as broken.
 */
@HiltViewModel
public class BinViewModel @Inject constructor(
    ledger: LedgerRepository,
    private val restoreEntries: RestoreEntryUseCase,
    private val purgeEntries: PurgeDeletedEntriesUseCase,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    public val state: StateFlow<BinUiState> = combine(
        ledger.observeDeleted(LedgerType.DEBIT),
        ledger.observeDeleted(LedgerType.CREDIT),
        local,
    ) { debits, credits, local ->
        val merged = (debits + credits).sortedByDescending { it.occurredAt }
        BinUiState(
            entries = merged,
            // Anything that has left the bin since the user ticked it — because
            // they restored it, or because it was purged — drops out of the
            // selection rather than lingering as a key pointing at nothing.
            selected = local.selected intersect merged.map { it.selectionKey() }.toSet(),
            isWorking = local.isWorking,
            confirmation = local.confirmation,
            message = local.message,
            isLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BinUiState())

    public fun onEvent(event: BinEvent) {
        when (event) {
            is BinEvent.Toggled -> local.update { current ->
                current.copy(
                    selected = if (event.key in current.selected) {
                        current.selected - event.key
                    } else {
                        current.selected + event.key
                    },
                )
            }

            BinEvent.SelectAllToggled -> local.update { current ->
                val all = state.value.entries.map { it.selectionKey() }.toSet()
                current.copy(selected = if (current.selected == all) emptySet() else all)
            }

            // No confirmation: it puts something back, and the worst outcome is
            // that the user bins it again.
            BinEvent.RestoreRequested -> restore()

            BinEvent.PurgeSelectedRequested -> ask(
                BinConfirmation.PurgeSelected(selectedRefs().size),
            )
            BinEvent.PurgeAllRequested -> ask(
                BinConfirmation.PurgeAll(state.value.entries.size),
            )

            BinEvent.ConfirmationAccepted -> accept()
            BinEvent.ConfirmationDismissed -> local.update { it.copy(confirmation = null) }

            BinEvent.MessageDismissed -> local.update { it.copy(message = null) }
        }
    }

    private fun ask(confirmation: BinConfirmation) {
        local.update { it.copy(confirmation = confirmation) }
    }

    /**
     * Exhaustive over the sealed type with no `else`, so a third question
     * cannot ship without someone deciding what accepting it destroys.
     */
    private fun accept() {
        when (local.value.confirmation) {
            null -> Unit
            is BinConfirmation.PurgeSelected -> purgeSelected()
            is BinConfirmation.PurgeAll -> purgeAll()
        }
    }

    private fun restore() {
        val refs = selectedRefs()
        if (refs.isEmpty()) return
        work { restored ->
            val count = restoreEntries(refs)
            restored(count, "Restored $count ${entryWord(count)}.")
        }
    }

    private fun purgeSelected() {
        val refs = selectedRefs()
        work { done ->
            val count = purgeEntries(refs)
            done(count, "Erased $count ${entryWord(count)} for good.")
        }
    }

    private fun purgeAll() {
        work { done ->
            val count = purgeEntries()
            done(count, "Erased $count ${entryWord(count)} for good.")
        }
    }

    /**
     * Runs one action, with the screen locked while it does.
     *
     * `isWorking` is not decoration: a purge compacts the database afterwards,
     * which rewrites the whole file, so this is the longest-running thing the
     * app does and a second tap through it must not be possible.
     *
     * The selection is cleared on the way out whatever happened — every row it
     * pointed at has either moved or gone, so keeping the ticks would leave the
     * user looking at a selection of nothing.
     */
    private fun work(block: suspend (done: (Int, String) -> Unit) -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(isWorking = true, confirmation = null) }
            block { _, message ->
                local.update {
                    it.copy(isWorking = false, selected = emptySet(), message = message)
                }
            }
        }
    }

    /**
     * The ticked rows, resolved to the pair every write needs.
     *
     * **Selection is read from [local], not from [state].** `state` is a
     * `combine` that only recomputes when the scheduler gets round to it, so
     * two events arriving back to back -- tick a row, then tap Erase -- would
     * see a `state` that predates the tick and act on an empty selection. The
     * entries still come from `state`, because those genuinely originate in the
     * database; the selection originates here.
     *
     * Filtering the entries by key also drops any tick pointing at a row that
     * has since left the bin, so a stale key can never become a write.
     */
    private fun selectedRefs(): List<BinnedRef> {
        val keys = local.value.selected
        return state.value.entries
            .filter { it.selectionKey() in keys }
            .map { BinnedRef(it.ledger, it.id) }
    }

    private fun entryWord(count: Int) = if (count == 1) "entry" else "entries"

    private data class LocalState(
        val selected: Set<String> = emptySet(),
        val isWorking: Boolean = false,
        val confirmation: BinConfirmation? = null,
        val message: String? = null,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The entries currently ticked, for a screen that needs them resolved. */
internal fun BinUiState.selectedEntries(): List<DeletedEntry> =
    entries.filter { it.selectionKey() in selected }
