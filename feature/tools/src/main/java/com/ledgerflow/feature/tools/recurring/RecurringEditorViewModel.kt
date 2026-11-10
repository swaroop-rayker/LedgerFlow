package com.ledgerflow.feature.tools.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.RecurringDetails
import com.ledgerflow.core.domain.recurring.RecurringError
import com.ledgerflow.core.domain.recurring.RecurringPassTrigger
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.recurring.RecurringResult
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.usecase.GetRecurringSuggestionsUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.ui.picker.LfPickerOption
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * New recurring and Edit recurring (V2-7; mockup 3), and "Add" on a
 * suggestion, which opens this form filled from what detection saw.
 *
 * **Saving asks for a pass soon** ([RecurringPassTrigger]), so an item whose
 * reminder day is today hears it today rather than at tomorrow's 09:00.
 */
@HiltViewModel
public class RecurringEditorViewModel @Inject constructor(
    private val recurring: RecurringRepository,
    private val suggestions: GetRecurringSuggestionsUseCase,
    private val merchants: MerchantRepository,
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val trigger: RecurringPassTrigger,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingId = savedStateHandle.get<String>(RecurringItemViewModel.ITEM_ID_ARG)?.takeIf { it.isNotEmpty() }
    private val fromMerchant = savedStateHandle.get<String>(FROM_MERCHANT_ARG)?.takeIf { it.isNotEmpty() }
    private val fromLedger = savedStateHandle.get<String>(FROM_LEDGER_ARG)?.let(LedgerType::fromStorage)

    private val _state = MutableStateFlow(RecurringEditorUiState(isEdit = editingId != null))
    public val state: StateFlow<RecurringEditorUiState> = _state.asStateFlow()

    /** A subcategory's parent, so a picked subcategory saves as the pair the ledger uses. */
    private var parentOf: Map<String, String> = emptyMap()

    init {
        load()
    }

    public fun onEvent(event: RecurringEditorEvent) {
        when (event) {
            is RecurringEditorEvent.NameChanged -> _state.update { it.copy(name = event.text, error = null) }
            is RecurringEditorEvent.LedgerPicked -> pickLedger(event.ledger)
            is RecurringEditorEvent.AmountChanged -> _state.update { it.copy(amountText = event.text, error = null) }
            RecurringEditorEvent.AmountVariesToggled -> _state.update { it.copy(amountVaries = !it.amountVaries) }
            is RecurringEditorEvent.CadencePicked -> _state.update { it.copy(cadence = event.cadence, error = null) }
            is RecurringEditorEvent.EveryDaysChanged -> _state.update {
                it.copy(everyDaysText = event.text, error = null)
            }
            is RecurringEditorEvent.PickerOpened -> _state.update { it.copy(openPicker = event.picker) }
            is RecurringEditorEvent.DuePicked -> _state.update { it.copy(firstDue = event.epochDay, openPicker = null) }
            is RecurringEditorEvent.MerchantPicked -> _state.update { current ->
                current.copy(
                    merchantId = event.id,
                    merchantName = current.merchantOptions.firstOrNull { it.id == event.id }?.label,
                    openPicker = null,
                )
            }
            is RecurringEditorEvent.CategoryPicked -> _state.update { current ->
                current.copy(
                    categoryChoice = event.id,
                    categoryLabel = current.categoryOptions.firstOrNull { it.id == event.id }?.label,
                    openPicker = null,
                )
            }
            is RecurringEditorEvent.RemindPicked -> _state.update { it.copy(remindDaysBefore = event.daysBefore) }
            RecurringEditorEvent.ToInboxToggled -> _state.update { it.copy(toInbox = !it.toInbox) }
            RecurringEditorEvent.SaveClicked -> save()
        }
    }

    private fun load() {
        viewModelScope.launch {
            val currency = ledger.baseCurrency() ?: DEFAULT_CURRENCY
            val today = LocalDates.of(clock.nowMillis())
            val merchantOptions = merchants.observeAll().first()
                .sortedBy { it.canonicalName.lowercase() }
                .map { LfPickerOption(it.id, it.canonicalName) }
            _state.update { it.copy(currency = currency, merchantOptions = merchantOptions, firstDue = today) }
            when {
                editingId != null -> fillFromItem(editingId, currency)
                fromMerchant != null && fromLedger != null ->
                    fillFromSuggestion(fromMerchant, fromLedger, currency, today)
                else -> loadCategories(LedgerType.DEBIT)
            }
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun fillFromItem(id: String, currency: String) {
        val item = recurring.observeItems().first().firstOrNull { it.id == id } ?: return
        loadCategories(item.ledger)
        val choice = item.subcategoryId ?: item.categoryId
        _state.update { current ->
            current.copy(
                name = item.name,
                ledger = item.ledger,
                amountText = MoneyFormat.plain(item.amount.minor, currency),
                amountVaries = item.amountVaries,
                cadence = item.cadence,
                everyDaysText = item.everyDays?.toString().orEmpty(),
                firstDue = item.nextDue,
                merchantId = item.merchantId,
                merchantName = current.merchantOptions.firstOrNull { it.id == item.merchantId }?.label,
                categoryChoice = choice,
                categoryLabel = current.categoryOptions.firstOrNull { it.id == choice }?.label,
                remindDaysBefore = item.remindDaysBefore,
                toInbox = item.toInbox,
            )
        }
    }

    private suspend fun fillFromSuggestion(merchantId: String, book: LedgerType, currency: String, today: Int) {
        loadCategories(book)
        val offer = suggestions().firstOrNull { it.merchantId == merchantId && it.ledger == book } ?: return
        _state.update { current ->
            current.copy(
                name = offer.name,
                ledger = book,
                amountText = MoneyFormat.plain(offer.amount.minor, currency),
                cadence = offer.cadence,
                everyDaysText = offer.everyDays?.toString().orEmpty(),
                // Detection's forecast, unless it has already passed.
                firstDue = maxOf(offer.nextExpected, today),
                merchantId = merchantId,
                merchantName = current.merchantOptions.firstOrNull { it.id == merchantId }?.label ?: offer.name,
            )
        }
    }

    /** A category belongs to one book, so changing the book clears it. */
    private fun pickLedger(book: LedgerType) {
        if (book == _state.value.ledger) return
        _state.update { it.copy(ledger = book, categoryChoice = null, categoryLabel = null) }
        viewModelScope.launch { loadCategories(book) }
    }

    private suspend fun loadCategories(book: LedgerType) {
        val trees = categories.observeTree(book).first()
        parentOf = trees.flatMap { tree -> tree.children.map { it.id to tree.parent.id } }.toMap()
        val options = trees.flatMap { tree ->
            listOf(LfPickerOption(tree.parent.id, tree.parent.name)) +
                tree.children.map { LfPickerOption(it.id, "${tree.parent.name} / ${it.name}") }
        }
        _state.update { it.copy(categoryOptions = options) }
    }

    private fun save() {
        val form = _state.value
        if (form.saving) return
        val choice = form.categoryChoice
        val details = RecurringDetails(
            name = form.name,
            ledger = form.ledger,
            amount = Money(MoneyFormat.parse(form.amountText, form.currency)),
            amountVaries = form.amountVaries,
            merchantId = form.merchantId,
            categoryId = choice?.let { parentOf[it] ?: it },
            subcategoryId = choice?.takeIf { it in parentOf },
            cadence = form.cadence,
            everyDays = form.everyDaysText.trim().toIntOrNull().takeIf { form.cadence == Cadence.EVERY_N_DAYS },
            firstDue = form.firstDue,
            remindDaysBefore = form.remindDaysBefore,
            toInbox = form.toInbox,
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = editingId?.let { recurring.update(it, details) } ?: recurring.create(details)
            when (result) {
                is RecurringResult.Success -> {
                    trigger.runSoon()
                    _state.update { it.copy(saving = false, done = true) }
                }
                is RecurringResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.error.toMessage())
                }
            }
        }
    }

    public companion object {
        /** "Add" on a suggestion: the merchant, and the book it was detected in. */
        public const val FROM_MERCHANT_ARG: String = "fromMerchant"
        public const val FROM_LEDGER_ARG: String = "fromLedger"
        private const val DEFAULT_CURRENCY = "INR"
    }
}

internal fun RecurringError.toMessage(): String = when (this) {
    RecurringError.NameBlank -> "Give it a name"
    RecurringError.AmountNotPositive -> "Enter an amount above zero"
    RecurringError.EveryDaysMissing -> "Enter how many days apart"
    RecurringError.ReminderNotOffered -> "Pick when to be reminded"
    is RecurringError.NotFound -> "That item no longer exists"
}
