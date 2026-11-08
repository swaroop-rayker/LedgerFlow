package com.ledgerflow.feature.settings.receipts

import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptImage
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptsRepository
import com.ledgerflow.core.domain.receipts.RemovalOutcome
import com.ledgerflow.core.domain.receipts.RemoveReceiptsFromPhoneUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One receipt's page (V2-5). */
@Immutable
public data class ReceiptUiState(
    /** Null until read. */
    val receipt: ReceiptListItem? = null,
    val image: ReceiptPicture = ReceiptPicture.Loading,
    val copies: ReceiptCopies? = null,
    val confirmingDelete: Boolean = false,
    val confirmingRemove: Boolean = false,
    val working: Boolean = false,
    /** Why a removal left it on the phone, or that it was removed. */
    val message: String? = null,
    /** Deleted here, or no longer exists: the page closes itself. */
    val gone: Boolean = false,
) {
    /**
     * Offered for an image on the phone, while Google Drive backup is on, and
     * never for one still waiting in the Inbox (ADR-0035 §2).
     */
    val canRemoveFromPhone: Boolean
        get() = image is ReceiptPicture.Shown &&
            copies?.drive == true &&
            receipt?.removedFromPhoneAt == null &&
            receipt?.filing !is com.ledgerflow.core.domain.receipts.ReceiptFiling.InInbox
}

/** The image as the page draws it. */
public sealed interface ReceiptPicture {
    public data object Loading : ReceiptPicture

    public class Shown(public val bitmap: ImageBitmap) : ReceiptPicture

    /** Missing or damaged on this phone (ADR-0023's honest degradation). */
    public data object Unavailable : ReceiptPicture

    /** Removed from this phone on purpose at [at]; Google Drive keeps a copy (ADR-0035). */
    public data class RemovedFromPhone(val at: Long) : ReceiptPicture
}

public sealed interface ReceiptEvent {
    public data object DeleteRequested : ReceiptEvent
    public data object DeleteDismissed : ReceiptEvent
    public data object DeleteConfirmed : ReceiptEvent
    public data object RemoveRequested : ReceiptEvent
    public data object RemoveDismissed : ReceiptEvent
    public data object RemoveConfirmed : ReceiptEvent
    public data object MessageDismissed : ReceiptEvent
}

/**
 * Reads the row and decrypts the image **into memory only**: never written to
 * `cacheDir` or anywhere else (Law 5), and dropped with the page.
 */
@HiltViewModel
public class ReceiptViewModel @Inject constructor(
    private val receipts: ReceiptsRepository,
    private val removeFromPhone: RemoveReceiptsFromPhoneUseCase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>(ATTACHMENT_ID_ARG).orEmpty()

    private val local = MutableStateFlow(ReceiptUiState())
    public val state: StateFlow<ReceiptUiState> = local.asStateFlow()

    init {
        viewModelScope.launch {
            val receipt = receipts.receipt(id)
            if (receipt == null) {
                local.update { it.copy(gone = true) }
                return@launch
            }
            local.update { it.copy(receipt = receipt, copies = receipts.copies()) }
            val picture = when (val image = receipts.image(id)) {
                is ReceiptImage.Available -> decode(image.bytes)
                ReceiptImage.Unavailable -> ReceiptPicture.Unavailable
                is ReceiptImage.RemovedFromPhone -> ReceiptPicture.RemovedFromPhone(image.at)
            }
            local.update { it.copy(image = picture) }
        }
    }

    public fun onEvent(event: ReceiptEvent) {
        when (event) {
            ReceiptEvent.DeleteRequested -> local.update { it.copy(confirmingDelete = true) }
            ReceiptEvent.DeleteDismissed -> local.update { it.copy(confirmingDelete = false) }
            ReceiptEvent.DeleteConfirmed -> viewModelScope.launch {
                local.update { it.copy(confirmingDelete = false, working = true) }
                val deletion = receipts.delete(setOf(id))
                local.update { it.copy(working = false, gone = deletion.deleted > 0) }
            }
            ReceiptEvent.RemoveRequested -> local.update { it.copy(confirmingRemove = true) }
            ReceiptEvent.RemoveDismissed -> local.update { it.copy(confirmingRemove = false) }
            ReceiptEvent.RemoveConfirmed -> viewModelScope.launch { remove() }
            ReceiptEvent.MessageDismissed -> local.update { it.copy(message = null) }
        }
    }

    /**
     * Checks Drive and removes this one receipt (ADR-0035). On success the page
     * shows where it is now; otherwise it stays, and the message says why.
     */
    private suspend fun remove() {
        local.update { it.copy(confirmingRemove = false, working = true) }
        val outcome = removeFromPhone(setOf(id))
        val gone = outcome is RemovalOutcome.Checked && outcome.removed == 1
        val refreshed = if (gone) receipts.receipt(id) else null
        local.update { state ->
            state.copy(
                working = false,
                receipt = refreshed ?: state.receipt,
                image = refreshed?.removedFromPhoneAt?.let(ReceiptPicture::RemovedFromPhone) ?: state.image,
                // The new state says it on its own; a notice is for when it stayed.
                message = if (gone) null else ReceiptsText.removed(outcome),
            )
        }
    }

    private suspend fun decode(bytes: ByteArray): ReceiptPicture = withContext(io) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.let { ReceiptPicture.Shown(it.asImageBitmap()) }
            ?: ReceiptPicture.Unavailable
    }

    public companion object {
        /**
         * The route's argument, read by string because `:feature:settings`
         * cannot see `Destination.Receipt` in `:app`. `ReceiptArgumentTest`
         * holds the two halves together.
         */
        public const val ATTACHMENT_ID_ARG: String = "attachmentId"
    }
}
