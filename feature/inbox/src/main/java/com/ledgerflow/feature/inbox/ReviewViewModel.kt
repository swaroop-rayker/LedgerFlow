package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.QuantityFormat
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.usecase.ApprovalEdits
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.GetPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * must not be rewritten underneath them because the row changed on disk. The
 * taxonomy *is* observed, because a merchant created from the picker has to
 * appear in the list that offered to create it.
 *
 * The state machine is the entry form's, reproduced field for field because the
 * two screens must behave identically (the owner's requirement). What is not
 * reproduced is `draft_entry`: a candidate is not a draft, and giving one a
 * draft row would put a half-reviewed message in the drafts stack where
 * discarding it in one place leaves it alive in the other.
 */
@HiltViewModel
public class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPending: GetPendingUseCase,
    private val approvePending: ApprovePendingUseCase,
    private val discardPending: DiscardPendingUseCase,
    private val observeCategoryTree: ObserveCategoryTreeUseCase,
    private val merchants: MerchantRepository,
    private val paymentMethods: PaymentMethodRepository,
    private val ledgerRepository: LedgerRepository,
    private val ids: Uuid7Generator,
) : ViewModel() {

    /**
     * The route argument, read by string.
     *
     * `:feature:inbox` cannot reference `Destination.InboxReview` — routes live
     * in `:app` precisely so features never depend on each other (CLAUDE.md §3)
     * — so the property name there and [PENDING_ID_ARG] here are two halves of
     * one contract. `InboxReviewArgumentTest` fails the build if they drift.
     */
    private val pendingId: String = requireNotNull(savedStateHandle[PENDING_ID_ARG]) {
        "ReviewViewModel needs a $PENDING_ID_ARG argument"
    }

    /**
     * The install's base currency.
     *
     * `amount_minor` is always base currency (D-02), so a review screen never
     * needs a second one — a foreign-currency message arrives with its original
     * figure and the user supplies the base amount, which is what this field
     * holds.
     */
    private var currency: String = DEFAULT_CURRENCY

    private val _state = MutableStateFlow(ReviewUiState(pendingId = pendingId))
    public val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
        observeTaxonomy()
    }

    private suspend fun load() {
        currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY
        val candidate = getPending(pendingId)
        if (candidate == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        _state.update { candidate.toUiState(it) }
        _state.value.ledger?.let(::observeCategoriesFor)
    }

    /**
     * The repositories directly rather than through forwarding use cases —
     * `:feature:entry`'s standing pattern, for its stated reason: a use case
     * that only forwards a call adds a name and a file, not a guarantee.
     *
     * Merchants and payment methods are book-independent; categories are not.
     *
     * The category tree is per ledger (Law 2), so it can only be fetched once a
     * book is known — which for an unread direction is after the user picks one.
     */
    private fun observeTaxonomy() {
        viewModelScope.launch {
            merchants.observeAll().collect { all -> _state.update { it.copy(merchants = all) } }
        }
        viewModelScope.launch {
            paymentMethods.observeAll().collect { all ->
                _state.update { it.copy(paymentMethods = all) }
            }
        }
    }

    private fun observeCategoriesFor(ledger: LedgerType) {
        viewModelScope.launch {
            observeCategoryTree(ledger).collect { trees ->
                val flattened = trees.flatMap { listOf(it.parent) + it.children }
                _state.update { current ->
                    current.copy(
                        categories = flattened,
                        // A category chosen under the other book is not valid
                        // here. Clearing beats sending the approval a category
                        // the ledger will refuse.
                        categoryId = current.categoryId?.takeIf { id -> flattened.any { it.id == id } },
                        subcategoryId = current.subcategoryId
                            ?.takeIf { id -> flattened.any { it.id == id } },
                    )
                }
            }
        }
    }

    /**
     * One entry point, four handlers.
     *
     * Split the way `:feature:entry` splits its own: the screen still sends
     * everything through a single `(ReviewEvent) -> Unit` (CLAUDE.md §5), and
     * the grouping below is about keeping each `when` small enough to read
     * rather than about the screen knowing which is which.
     */
    public fun onEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.AmountChanged -> _state.update { it.copy(amountText = event.text) }
            is ReviewEvent.NoteChanged -> _state.update { it.copy(noteText = event.text) }

            is ReviewEvent.PickerOpened,
            ReviewEvent.PickerDismissed,
            is ReviewEvent.PickerItemSelected,
            is ReviewEvent.MerchantQueryChanged,
            ReviewEvent.MerchantCreateRequested,
            ReviewEvent.DateRequested,
            is ReviewEvent.DateSelected,
            ReviewEvent.DateDismissed,
            -> onChooserEvent(event)

            is ReviewEvent.ModeSelected,
            ReviewEvent.SingleItemConfirmed,
            ReviewEvent.SingleItemDismissed,
            ReviewEvent.LineAdded,
            is ReviewEvent.LineExpanded,
            ReviewEvent.LineCollapsed,
            is ReviewEvent.LineNameChanged,
            is ReviewEvent.LineUnitPriceChanged,
            is ReviewEvent.LineQuantityChanged,
            is ReviewEvent.LineRemoved,
            is ReviewEvent.LineCategoryRequested,
            is ReviewEvent.LineSubcategoryRequested,
            -> onLineEvent(event)

            ReviewEvent.Approve -> approve()
            ReviewEvent.Discard -> viewModelScope.launch {
                discardPending(pendingId)
                _state.update { it.copy(finished = true) }
            }

            ReviewEvent.MessageShown -> _state.update { it.copy(message = null) }
        }
    }

    /** Pickers and the date dialog: everything that opens something. */
    private fun onChooserEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.PickerOpened -> _state.update { it.copy(picker = event.picker) }
            ReviewEvent.PickerDismissed -> _state.update { it.copy(picker = null) }
            is ReviewEvent.PickerItemSelected -> select(event.id)

            is ReviewEvent.MerchantQueryChanged ->
                _state.update { it.copy(merchantQuery = event.text) }

            ReviewEvent.MerchantCreateRequested -> createTypedMerchant()

            ReviewEvent.DateRequested -> _state.update { it.copy(choosingDate = true) }
            is ReviewEvent.DateSelected ->
                _state.update { it.copy(occurredAt = event.epochMillis, choosingDate = false) }
            ReviewEvent.DateDismissed -> _state.update { it.copy(choosingDate = false) }

            else -> Unit
        }
    }

    /** `Single item | Itemised` and everything inside the line editor (ADR-0018). */
    private fun onLineEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.ModeSelected -> setMode(event.itemised)
            ReviewEvent.SingleItemConfirmed -> _state.update { it.asSingleItem() }
            ReviewEvent.SingleItemDismissed ->
                _state.update { it.copy(confirmingSingleItem = false) }

            ReviewEvent.LineAdded -> _state.update { it.withNewLine(ids.generate()) }
            is ReviewEvent.LineExpanded -> _state.update { it.copy(expandedLineKey = event.key) }
            ReviewEvent.LineCollapsed -> _state.update { it.copy(expandedLineKey = null) }

            is ReviewEvent.LineNameChanged,
            is ReviewEvent.LineUnitPriceChanged,
            is ReviewEvent.LineQuantityChanged,
            -> onLineFieldEvent(event)

            is ReviewEvent.LineRemoved -> _state.update { current ->
                current.copy(
                    lines = current.lines.filterNot { it.key == event.key },
                    expandedLineKey = current.expandedLineKey?.takeIf { it != event.key },
                )
            }

            is ReviewEvent.LineCategoryRequested ->
                _state.update { it.copy(picker = ReviewPicker.Category(lineKey = event.key)) }

            is ReviewEvent.LineSubcategoryRequested -> _state.update { current ->
                val parentId = current.lines.firstOrNull { it.key == event.key }?.categoryId
                    ?: return@update current
                current.copy(picker = ReviewPicker.Subcategory(parentId, lineKey = event.key))
            }

            else -> Unit
        }
    }

    /** Applies a picker's choice to whatever it was opened for. */
    private fun select(id: String?) {
        _state.update { current ->
            val next = when (val picker = current.picker) {
                null -> current

                is ReviewPicker.Category -> when (val key = picker.lineKey) {
                    // A line's category changing invalidates its subcategory:
                    // §6.1.1's invariant is that the parent *is* the category.
                    null -> current.copy(categoryId = id, subcategoryId = null)
                    else -> current.mapLine(key) { it.copy(categoryId = id, subcategoryId = null) }
                }

                is ReviewPicker.Subcategory -> when (val key = picker.lineKey) {
                    null -> current.copy(subcategoryId = id)
                    else -> current.mapLine(key) { it.copy(subcategoryId = id) }
                }

                ReviewPicker.Merchant -> current.copy(merchantId = id)
                ReviewPicker.PaymentMethod -> current.copy(paymentMethodId = id)

                ReviewPicker.Book -> current.copy(
                    ledger = id?.let(LedgerType::valueOf),
                    // The tree is per book, so anything filed under the old one
                    // has to go with it.
                    categoryId = null,
                    subcategoryId = null,
                )
            }
            next.copy(picker = null)
        }

        // Choosing the book is what unlocks the category list for it.
        if (_state.value.picker == null) {
            _state.value.ledger?.takeIf { _state.value.categories.isEmpty() }
                ?.let(::observeCategoriesFor)
        }
    }

    private fun createTypedMerchant() {
        val name = _state.value.merchantQuery.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            // `createOrGet`, not a create: the field is a search box as much
            // as a name box, so typing one that already exists selects it. It
            // also un-hides a hidden merchant, bringing its aliases and default
            // category back with it (BUG11).
            when (val result = merchants.createOrGet(name)) {
                is TaxonomyResult.Success -> _state.update {
                    it.copy(merchantId = result.value.id, picker = null, merchantQuery = "")
                }

                is TaxonomyResult.Failure ->
                    _state.update { it.copy(message = "Could not add that merchant.") }
            }
        }
    }

    /**
     * `Single item | Itemised` (ADR-0018).
     *
     * Leaving itemised mode discards lines, so it asks first — but only when
     * there is something to lose. A dialog over an empty editor is a dialog
     * about nothing, which is how people learn to dismiss dialogs unread.
     */
    private fun setMode(itemised: Boolean) {
        _state.update { current ->
            when {
                itemised == current.itemised -> current
                itemised -> current.asItemised(ids.generate())
                current.lines.any { it.hasContent } -> current.copy(confirmingSingleItem = true)
                else -> current.asSingleItem()
            }
        }
    }

    /**
     * The three text fields inside a line.
     *
     * Held as raw text and parsed on every keystroke, for the reason the entry
     * amount is: reformatting a number under a moving caret makes the field
     * unusable, so the text is what the user typed and the minor units are
     * derived beside it.
     */
    private fun onLineFieldEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.LineNameChanged ->
                _state.update { it.mapLine(event.key) { line -> line.copy(name = event.value) } }

            is ReviewEvent.LineUnitPriceChanged -> _state.update {
                it.mapLine(event.key) { line ->
                    line.copy(
                        unitPriceText = event.text,
                        unitPriceMinor = MoneyFormat.parse(event.text, currency),
                    )
                }
            }

            is ReviewEvent.LineQuantityChanged -> _state.update {
                it.mapLine(event.key) { line ->
                    line.copy(
                        quantityText = event.text,
                        quantityMilli = QuantityFormat.parse(event.text),
                    )
                }
            }

            else -> Unit
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
                    occurredAt = current.occurredAt,
                    // The picked merchant wins; otherwise the name the message
                    // carried, which the approval resolves through createOrGet.
                    merchantId = current.merchantId,
                    merchantName = if (current.merchantId == null) current.rawMerchantName else null,
                    categoryId = current.categoryId,
                    subcategoryId = current.subcategoryId,
                    paymentMethodId = current.paymentMethodId,
                    note = current.noteText.trim().takeIf { it.isNotEmpty() },
                    lineItems = current.newLineItems(),
                ),
            ).fold(
                onSuccess = { _state.update { it.copy(submitting = false, finished = true) } },
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
     * empty rather than defaulted. **The book is derived here and nowhere
     * else** — "debited" is spend, "credited" is income — and the only time the
     * screen offers a choice is when the parser read neither.
     */
    private fun PendingTransaction.toUiState(previous: ReviewUiState): ReviewUiState {
        val book = extracted.direction.toLedgerOrNull()
        return previous.copy(
            loading = false,
            missing = false,
            ledger = book,
            bookIsUnread = book == null,
            amountText = extracted.amount?.let { MoneyFormat.plain(it.minor, currency) }.orEmpty(),
            // The message's own time when it stated one, the capture time
            // otherwise -- a fact about something that happened, rather than a
            // guessed date.
            occurredAt = extracted.occurredAt ?: createdAt,
            rawMerchantName = extracted.merchantRaw,
            // Pre-selected only when the message's payee already exists as a
            // merchant. Otherwise the row shows the raw name and `createOrGet`
            // makes it real at approval (§5.1) -- which is what stops a
            // discarded candidate leaving a merchant behind.
            merchantId = previous.merchants
                .firstOrNull { it.normalizedKey == extracted.merchantRaw?.let(MerchantNormalizer::normalize) }
                ?.id,
            needsManualFill = needsManualFill,
            sourceLabel = when (source) {
                EntrySource.SMS -> "From an SMS"
                EntrySource.NOTIFICATION -> "From a notification"
                EntrySource.OCR -> "From a receipt"
                EntrySource.MANUAL -> "Entered by hand"
                EntrySource.IMPORT -> "Imported"
            },
            referenceHint = extracted.referenceNo?.let { "Ref $it" },
        )
    }

    /** Lines worth saving, as the approval wants them. */
    private fun ReviewUiState.newLineItems(): List<NewLineItem> =
        if (!itemised) {
            emptyList()
        } else {
            lines.filter { it.hasContent }.map { line ->
                NewLineItem(
                    name = line.name,
                    total = Money(line.amountMinor),
                    quantityMilli = line.quantityMilli,
                    unitPrice = Money(line.unitPriceMinor),
                    categoryId = line.categoryId,
                    subcategoryId = line.subcategoryId,
                )
            }
        }

    public companion object {
        /** Must equal `Destination.InboxReview`'s property name. */
        public const val PENDING_ID_ARG: String = "pendingId"

        /** Until `app_meta` answers. Onboarding guarantees a real one exists. */
        private const val DEFAULT_CURRENCY = "INR"
    }
}

// ── Pure form transforms, top-level for the reason the entry form's are ───────

/**
 * Into itemised mode (ADR-0018).
 *
 * The candidate's category moves *down* onto the first line rather than being
 * discarded: the user has already answered "what is this", and the answer is
 * still true of at least part of the bill.
 */
internal fun ReviewUiState.asItemised(newKey: String): ReviewUiState {
    val seeded = lines.ifEmpty {
        listOf(ReviewLine(key = newKey, categoryId = categoryId, subcategoryId = subcategoryId))
    }
    return copy(
        itemised = true,
        categoryId = null,
        subcategoryId = null,
        lines = seeded,
        expandedLineKey = seeded.firstOrNull()?.key,
    )
}

/** Back to a single item. Destructive, so the caller confirms first. */
internal fun ReviewUiState.asSingleItem(): ReviewUiState = copy(
    itemised = false,
    lines = emptyList(),
    expandedLineKey = null,
    confirmingSingleItem = false,
)

/**
 * Adds a line, pre-filed like the one above it.
 *
 * A twelve-line grocery bill is mostly one category with two exceptions, so
 * inheriting turns twelve category picks into two.
 */
internal fun ReviewUiState.withNewLine(newKey: String): ReviewUiState {
    val previous = lines.lastOrNull()
    val line = ReviewLine(
        key = newKey,
        categoryId = previous?.categoryId ?: categoryId,
        subcategoryId = previous?.subcategoryId ?: subcategoryId,
    )
    return copy(lines = lines + line, expandedLineKey = line.key)
}

internal fun ReviewUiState.mapLine(key: String, block: (ReviewLine) -> ReviewLine): ReviewUiState =
    copy(lines = lines.map { if (it.key == key) block(it) else it })
