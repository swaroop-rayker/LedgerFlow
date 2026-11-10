package com.ledgerflow.feature.tools.recurring

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.picker.LfPickerOption

/** A detected merchant offered as an item (mockup 1's "Looks recurring"). */
@Immutable
public data class SuggestionRowUi(
    val merchantId: String,
    val ledger: LedgerType,
    val name: String,
    val amount: String,
    /** "Every month, 4 times · next about 14 Nov". */
    val caption: String,
)

/** One item in its book's panel: about two lines. */
@Immutable
public data class RecurringRowUi(
    val id: String,
    val name: String,
    /** "₹25,000.00", or "~₹2,100.00" when the amount varies. */
    val amount: String,
    /** "Monthly · due 1 Dec · reminds 1 day before · to Inbox". */
    val caption: String,
    val paused: Boolean,
)

/** The Recurring page. Each book in its own panel, never a combined total (Law 2). */
@Immutable
public data class RecurringListUiState(
    val loading: Boolean = true,
    val suggestions: List<SuggestionRowUi> = emptyList(),
    val expense: List<RecurringRowUi> = emptyList(),
    val income: List<RecurringRowUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && suggestions.isEmpty() && expense.isEmpty() && income.isEmpty()
}

public sealed interface RecurringListEvent {
    public data class NotRecurring(val merchantId: String, val ledger: LedgerType) : RecurringListEvent
}

/** One due date in an item's "Recent" log. */
@Immutable
public data class OccurrenceRowUi(val date: String, val words: String)

/** One item's page (mockup 2). */
@Immutable
public data class RecurringItemUiState(
    val loading: Boolean = true,
    /** Deleted here or elsewhere: the page closes. */
    val gone: Boolean = false,
    val name: String = "",
    val amount: String = "",
    /** "Expense · Monthly". */
    val subtitle: String = "",
    val facts: List<String> = emptyList(),
    val recent: List<OccurrenceRowUi> = emptyList(),
    val paused: Boolean = false,
    val optionsOpen: Boolean = false,
    val confirmingDelete: Boolean = false,
    val message: String? = null,
)

public sealed interface RecurringItemEvent {
    public data object OptionsClicked : RecurringItemEvent
    public data object OptionsDismissed : RecurringItemEvent
    public data object PauseToggled : RecurringItemEvent
    public data object DeleteClicked : RecurringItemEvent
    public data object DeleteConfirmed : RecurringItemEvent
    public data object DeleteCancelled : RecurringItemEvent
    public data object MessageShown : RecurringItemEvent
}

/** Which of the form's pickers is open. */
public enum class RecurringPicker { DUE_DATE, MERCHANT, CATEGORY }

/** The form (mockup 3). */
@Immutable
public data class RecurringEditorUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val currency: String = "INR",
    val name: String = "",
    val ledger: LedgerType = LedgerType.DEBIT,
    val amountText: String = "",
    val amountVaries: Boolean = false,
    val cadence: Cadence = Cadence.MONTHLY,
    val everyDaysText: String = "",
    /** Days since epoch. */
    val firstDue: Int = 0,
    val merchantId: String? = null,
    val merchantName: String? = null,
    /** The category or subcategory chosen; [categoryLabel] names it. */
    val categoryChoice: String? = null,
    val categoryLabel: String? = null,
    val remindDaysBefore: Int = 1,
    val toInbox: Boolean = true,
    val merchantOptions: List<LfPickerOption> = emptyList(),
    val categoryOptions: List<LfPickerOption> = emptyList(),
    val openPicker: RecurringPicker? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
)

public sealed interface RecurringEditorEvent {
    public data class NameChanged(val text: String) : RecurringEditorEvent
    public data class LedgerPicked(val ledger: LedgerType) : RecurringEditorEvent
    public data class AmountChanged(val text: String) : RecurringEditorEvent
    public data object AmountVariesToggled : RecurringEditorEvent
    public data class CadencePicked(val cadence: Cadence) : RecurringEditorEvent
    public data class EveryDaysChanged(val text: String) : RecurringEditorEvent
    public data class PickerOpened(val picker: RecurringPicker?) : RecurringEditorEvent
    public data class DuePicked(val epochDay: Int) : RecurringEditorEvent
    public data class MerchantPicked(val id: String?) : RecurringEditorEvent
    public data class CategoryPicked(val id: String?) : RecurringEditorEvent
    public data class RemindPicked(val daysBefore: Int) : RecurringEditorEvent
    public data object ToInboxToggled : RecurringEditorEvent
    public data object SaveClicked : RecurringEditorEvent
}
