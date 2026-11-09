package com.ledgerflow.feature.settings.receipts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptCount
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.domain.receipts.ReceiptsRepository
import com.ledgerflow.core.domain.receipts.RemoveReceiptsFromPhoneUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Receipts page (V2-5, V2-PLAN §3.7). */
@Immutable
public data class ReceiptsUiState(
    val period: ReceiptPeriod = ReceiptPeriod.Month,
    /** The receipts in [period]. Null until read, so the line never flashes "0". */
    val count: ReceiptCount? = null,
    /** Every receipt on this phone, measured from the files (ADR-0023). */
    val onPhone: AttachmentUsage? = null,
    val copies: ReceiptCopies? = null,
    /** Removed from the phone, kept only in backups (ADR-0035). Null until read. */
    val removed: ReceiptCount? = null,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** The size of [selected], for the dialog: a delete names its size (ADR-0035 §5). */
    val selectedBytes: Long = 0L,
    val confirmation: ReceiptsConfirmation? = null,
    val working: Boolean = false,
    /** What the last delete or removal did. */
    val message: String? = null,
) {
    /** "Remove from phone" is offered only while Google Drive backup is on (ADR-0035). */
    val canRemoveFromPhone: Boolean get() = copies?.drive == true
}

/** The question on screen: a delete (irreversible) or a removal (Drive keeps a copy). */
public sealed interface ReceiptsConfirmation {
    public val count: Int
    public val bytes: Long

    public data class DeleteSelected(override val count: Int, override val bytes: Long) : ReceiptsConfirmation

    public data class DeleteAll(override val count: Int, override val bytes: Long) : ReceiptsConfirmation

    public data class RemoveSelected(override val count: Int, override val bytes: Long) : ReceiptsConfirmation
}

public sealed interface ReceiptsEvent {
    public data class PeriodSelected(val period: ReceiptPeriod) : ReceiptsEvent
    public data object SelectionStarted : ReceiptsEvent
    public data object SelectionCancelled : ReceiptsEvent
    public data class Toggled(val id: String, val bytes: Long) : ReceiptsEvent

    /** Takes every receipt the period shows, not only the rows loaded so far. */
    public data object SelectAll : ReceiptsEvent
    public data object DeleteSelectedRequested : ReceiptsEvent
    public data object RemoveSelectedRequested : ReceiptsEvent
    public data object DeleteAllRequested : ReceiptsEvent
    public data object ConfirmationAccepted : ReceiptsEvent
    public data object ConfirmationDismissed : ReceiptsEvent
    public data object MessageDismissed : ReceiptsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class ReceiptsViewModel @Inject constructor(
    private val receiptsRepository: ReceiptsRepository,
    private val attachments: AttachmentRepository,
    private val removeFromPhone: RemoveReceiptsFromPhoneUseCase,
) : ViewModel() {

    private val local = MutableStateFlow(ReceiptsUiState())

    /** Only a new period asks again: ticking a row must not rebuild the list. */
    private val period: Flow<ReceiptPeriod> = local.map { it.period }.distinctUntilChanged()

    /** Paged and cached here, so a rotation keeps its pages. */
    public val receipts: Flow<PagingData<ReceiptListItem>> = period
        .flatMapLatest { receiptsRepository.observeReceipts(it) }
        .cachedIn(viewModelScope)

    public val state: StateFlow<ReceiptsUiState> = combine(
        local,
        period.flatMapLatest { receiptsRepository.observeCount(it) },
        receiptsRepository.observeRemoved(),
    ) { state, count, removed -> state.copy(count = count, removed = removed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value)

    /** The phone's figure and the copies: measurements, re-read on resume. */
    public fun refresh() {
        viewModelScope.launch {
            val usage = attachments.usage()
            val copies = receiptsRepository.copies()
            local.update { it.copy(onPhone = usage, copies = copies) }
        }
    }

    public fun onEvent(event: ReceiptsEvent) {
        when (event) {
            is ReceiptsEvent.PeriodSelected -> local.update {
                // A selection belongs to what was shown: changing the period
                // ends it rather than hiding ticked rows the user cannot see.
                it.copy(period = event.period, selecting = false, selected = emptySet(), selectedBytes = 0L)
            }
            ReceiptsEvent.SelectionStarted -> local.update { it.copy(selecting = true) }
            ReceiptsEvent.SelectionCancelled -> local.update {
                it.copy(selecting = false, selected = emptySet(), selectedBytes = 0L)
            }
            is ReceiptsEvent.Toggled -> local.update { toggled(it, event) }
            ReceiptsEvent.SelectAll -> viewModelScope.launch { selectAll() }
            ReceiptsEvent.DeleteSelectedRequested,
            ReceiptsEvent.RemoveSelectedRequested,
            ReceiptsEvent.DeleteAllRequested,
            -> local.update { asked(it, event) }
            ReceiptsEvent.ConfirmationAccepted -> viewModelScope.launch { delete() }
            ReceiptsEvent.ConfirmationDismissed -> local.update { it.copy(confirmation = null) }
            ReceiptsEvent.MessageDismissed -> local.update { it.copy(message = null) }
        }
    }

    /** The question a request opens, or none (nothing selected; Drive off for a removal). */
    private fun asked(state: ReceiptsUiState, event: ReceiptsEvent): ReceiptsUiState {
        val question = when (event) {
            ReceiptsEvent.DeleteSelectedRequested -> state.selected.takeIf { it.isNotEmpty() }
                ?.let { ReceiptsConfirmation.DeleteSelected(it.size, state.selectedBytes) }
            ReceiptsEvent.RemoveSelectedRequested -> state.selected
                .takeIf { it.isNotEmpty() && state.canRemoveFromPhone }
                ?.let { ReceiptsConfirmation.RemoveSelected(it.size, state.selectedBytes) }
            // Asked even at zero, as the Settings row always was: a destructive
            // control that is sometimes inert teaches people to tap it without
            // reading. At zero the dialog says there is nothing to delete.
            ReceiptsEvent.DeleteAllRequested -> state.onPhone
                ?.let { ReceiptsConfirmation.DeleteAll(it.count, it.bytes) }
            else -> null
        }
        return question?.let { state.copy(confirmation = it) } ?: state
    }

    private fun toggled(state: ReceiptsUiState, event: ReceiptsEvent.Toggled): ReceiptsUiState =
        if (event.id in state.selected) {
            state.copy(selected = state.selected - event.id, selectedBytes = state.selectedBytes - event.bytes)
        } else {
            state.copy(selected = state.selected + event.id, selectedBytes = state.selectedBytes + event.bytes)
        }

    private suspend fun selectAll() {
        val period = local.value.period
        val ids = receiptsRepository.idsIn(period)
        val bytes = receiptsRepository.observeCount(period).first().bytes
        local.update { it.copy(selecting = true, selected = ids.toSet(), selectedBytes = bytes) }
    }

    private suspend fun delete() {
        val confirmation = local.value.confirmation ?: return
        // "No receipts to delete" has only OK; there is nothing to confirm.
        if (confirmation.count == 0) {
            local.update { it.copy(confirmation = null) }
            return
        }
        local.update { it.copy(confirmation = null, working = true) }
        val message = when (confirmation) {
            is ReceiptsConfirmation.DeleteSelected -> deleted(receiptsRepository.delete(local.value.selected))
            is ReceiptsConfirmation.DeleteAll -> deleted(receiptsRepository.deleteAll())
            is ReceiptsConfirmation.RemoveSelected -> ReceiptsText.removed(removeFromPhone(local.value.selected))
        }
        // Re-measured rather than assumed: a failed unlink shows as a file
        // still there, not as a clean slate the app merely hoped for.
        val usage = attachments.usage()
        local.update {
            it.copy(
                working = false,
                selecting = false,
                selected = emptySet(),
                selectedBytes = 0L,
                onPhone = usage,
                message = message,
            )
        }
    }

    private fun deleted(deletion: ReceiptDeletion): String =
        if (deletion.deleted == 0) ReceiptsText.NOTHING_DELETED else ReceiptsText.deleted(deletion)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
