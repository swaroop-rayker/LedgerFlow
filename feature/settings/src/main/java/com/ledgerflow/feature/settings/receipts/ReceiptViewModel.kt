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
    val working: Boolean = false,
    /** Deleted here, or no longer exists: the page closes itself. */
    val gone: Boolean = false,
)

/** The image as the page draws it. */
public sealed interface ReceiptPicture {
    public data object Loading : ReceiptPicture

    public class Shown(public val bitmap: ImageBitmap) : ReceiptPicture

    /** Missing or damaged on this phone (ADR-0023's honest degradation). */
    public data object Unavailable : ReceiptPicture
}

public sealed interface ReceiptEvent {
    public data object DeleteRequested : ReceiptEvent
    public data object DeleteDismissed : ReceiptEvent
    public data object DeleteConfirmed : ReceiptEvent
}

/**
 * Reads the row and decrypts the image **into memory only**: never written to
 * `cacheDir` or anywhere else (Law 5), and dropped with the page.
 */
@HiltViewModel
public class ReceiptViewModel @Inject constructor(
    private val receipts: ReceiptsRepository,
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
