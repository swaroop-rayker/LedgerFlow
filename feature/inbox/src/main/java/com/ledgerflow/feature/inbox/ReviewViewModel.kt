package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.usecase.ApprovalEdits
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.GetPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reviewing one candidate before it reaches the ledger (SPEC.md §5.1). P2-6.
 *
 * **Approving from here is the human act Law 1 requires.** It goes through
 * [ApprovePendingUseCase], which composes Law 1's single writer; nothing in this
 * class touches `ledger_entry`.
 *
 * The candidate is read **once**, not observed. A form the user is typing into
 * must not be rewritten underneath them because the row changed on disk — that
 * is the same reasoning `draft_entry` exists for, applied to a screen that has
 * no draft.
 */
@HiltViewModel
public class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPending: GetPendingUseCase,
    private val approvePending: ApprovePendingUseCase,
    private val discardPending: DiscardPendingUseCase,
    private val observeCategoryTree: ObserveCategoryTreeUseCase,
    private val ledgerRepository: LedgerRepository,
) : ViewModel() {

    /**
     * The route argument, read by string.
     *
     * `:feature:inbox` cannot reference `Destination.InboxReview` — routes live
     * in `:app` precisely so features never depend on each other (CLAUDE.md §3)
     * — so the property name there and [PENDING_ID_ARG] here are two halves of
     * one contract. `InboxReviewArgumentTest` fails the build if they drift,
     * the same guard `:feature:entry` already has for its draft id.
     */
    private val pendingId: String = requireNotNull(savedStateHandle[PENDING_ID_ARG]) {
        "ReviewViewModel needs a $PENDING_ID_ARG argument"
    }

    /**
     * The install's base currency, for parsing and formatting the amount field.
     *
     * Read once at load. `amount_minor` is always base currency (D-02), so a
     * review screen never needs a second one -- a foreign-currency message
     * reaches here with its original figure and the user supplies the base
     * amount, which is what this field holds.
     */
    private var currency: String = DEFAULT_CURRENCY

    private val _state = MutableStateFlow(ReviewUiState(pendingId = pendingId))
    public val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY
        val candidate = getPending(pendingId)
        if (candidate == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        _state.update { candidate.toUiState(it) }
        // The tree is per book (Law 2), so it can only be fetched once a book is
        // known -- which is why this follows the candidate rather than running
        // beside it.
        _state.value.ledger?.let { observeCategoriesFor(it) }
    }

    private fun observeCategoriesFor(ledger: LedgerType) {
        viewModelScope.launch {
            observeCategoryTree(ledger).collect { trees ->
                _state.update { current ->
                    val flattened = trees.flatMap { listOf(it.parent) + it.children }
                    current.copy(
                        categories = flattened,
                        // A category chosen under the other book is not valid
                        // here. Clearing beats silently sending the approval a
                        // category the ledger will refuse.
                        categoryId = current.categoryId?.takeIf { id ->
                            flattened.any { it.id == id }
                        },
                    )
                }
            }
        }
    }

    public fun onEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.LedgerChosen -> {
                _state.update { it.copy(ledger = event.ledger) }
                observeCategoriesFor(event.ledger)
            }

            is ReviewEvent.AmountChanged -> _state.update { it.copy(amountText = event.text) }
            is ReviewEvent.MerchantChanged -> _state.update { it.copy(merchantText = event.text) }
            is ReviewEvent.NoteChanged -> _state.update { it.copy(noteText = event.text) }
            is ReviewEvent.CategoryChosen -> _state.update { it.copy(categoryId = event.categoryId) }
            ReviewEvent.MessageShown -> _state.update { it.copy(message = null) }

            ReviewEvent.Approve -> approve()
            ReviewEvent.Discard -> viewModelScope.launch {
                discardPending(pendingId)
                _state.update { it.copy(approved = true) }
            }
        }
    }

    private fun approve() {
        val current = _state.value
        val ledger = current.ledger ?: return
        val minor = MoneyFormat.parse(current.amountText, currency)
        if (minor <= 0L) {
            _state.update { it.copy(message = "Enter an amount greater than zero.") }
            return
        }

        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            approvePending(
                pendingId = pendingId,
                edits = ApprovalEdits(
                    ledger = ledger,
                    amount = Money(minor),
                    merchantName = current.merchantText,
                    categoryId = current.categoryId,
                    note = current.noteText.trim().takeIf { it.isNotEmpty() },
                ),
            ).fold(
                onSuccess = { _state.update { it.copy(submitting = false, approved = true) } },
                onFailure = { error ->
                    _state.update {
                        it.copy(submitting = false, message = error.message ?: "Could not add it.")
                    }
                },
            )
        }
    }

    /**
     * Extraction -> form.
     *
     * Everything the parser found is filled in; everything it did not is left
     * empty rather than defaulted. A guessed book is the one that matters (Law
     * 2), so an `UNKNOWN` direction arrives as no selection and the user has to
     * choose before Approve is enabled.
     */
    private fun PendingTransaction.toUiState(previous: ReviewUiState) = previous.copy(
        loading = false,
        missing = false,
        ledger = extracted.direction.toLedgerOrNull(),
        amountText = extracted.amount?.let { MoneyFormat.plain(it.minor, currency) }.orEmpty(),
        merchantText = extracted.merchantRaw.orEmpty(),
        needsManualFill = needsManualFill,
        sourceLabel = when (source) {
            EntrySource.SMS -> "From an SMS"
            EntrySource.NOTIFICATION -> "From a notification"
            EntrySource.OCR -> "From a receipt"
            EntrySource.MANUAL -> "Entered by hand"
            EntrySource.IMPORT -> "Imported"
        },
        rawBodyHint = extracted.referenceNo?.let { "Ref $it" },
        occurredAtLabel = DATE_FORMAT.format(
            Instant.ofEpochMilli(extracted.occurredAt ?: createdAt).atZone(ZoneId.systemDefault()),
        ),
    )

    public companion object {
        /** Must equal `Destination.InboxReview`'s property name. See [pendingId]. */
        public const val PENDING_ID_ARG: String = "pendingId"

        /** Until `app_meta` answers. Onboarding guarantees a real one exists. */
        private const val DEFAULT_CURRENCY = "INR"

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}

/** Flattened tree entries render with their depth; a subcategory is indented by name. */
internal fun Category.displayName(): String = if (isSubcategory) "  $name" else name
