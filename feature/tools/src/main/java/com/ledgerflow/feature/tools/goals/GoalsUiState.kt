package com.ledgerflow.feature.tools.goals

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.ui.picker.LfPickerGroup

/** One goal as the list draws it: about two lines (CLAUDE.md §5, compact). */
@Immutable
public data class GoalRowUi(
    val id: String,
    val name: String,
    /** "₹62,000.00". */
    val saved: String,
    /** 0 to 1, for the bar. A ratio for drawing, not money (Law 3). */
    val fraction: Float,
    val caption: String,
    val warning: Boolean,
)

/** The Savings goals page. */
@Immutable
public data class GoalsUiState(
    val loading: Boolean = true,
    val active: List<GoalRowUi> = emptyList(),
    val reached: List<GoalRowUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && active.isEmpty() && reached.isEmpty()
}

/** One hand-added amount on a goal's page. */
@Immutable
public data class ContributionRowUi(
    val id: String,
    /** "2 Nov". */
    val date: String,
    val note: String?,
    /** "₹15,000", or "−₹2,000" for money taken out. */
    val amount: String,
    val takenOut: Boolean,
)

/** The goal's linked category, and what it holds. */
@Immutable
public data class LinkedCategoryUi(
    /** "From your Savings category · Expense book", or a removed category's line. */
    val heading: String,
    /** "Since 1 Aug 2026". */
    val since: String,
    val amount: String,
)

/** "Add money" or "Take out", new or an edit. */
@Immutable
public data class ContributionEditorState(
    val editingId: String? = null,
    val takingOut: Boolean = false,
    val amountText: String = "",
    val date: Int,
    val note: String = "",
    val showDatePicker: Boolean = false,
    val error: String? = null,
) {
    val title: String
        get() = when {
            editingId != null && takingOut -> "Edit money taken out"
            editingId != null -> "Edit money added"
            takingOut -> "Take out"
            else -> "Add money"
        }
}

/** One goal's page. */
@Immutable
public data class GoalUiState(
    val loading: Boolean = true,
    /** The goal no longer exists (deleted here or elsewhere): the page closes. */
    val gone: Boolean = false,
    val name: String = "",
    val currency: String = "INR",
    val saved: String = "",
    /** "of ₹1,00,000 · ₹38,000 to go". */
    val ofTarget: String = "",
    val fraction: Float = 0f,
    val warning: Boolean = false,
    val facts: List<GoalLineUi> = emptyList(),
    val linked: LinkedCategoryUi? = null,
    val contributions: List<ContributionRowUi> = emptyList(),
    val editor: ContributionEditorState? = null,
    val optionsOpen: Boolean = false,
    val confirmingDelete: Boolean = false,
    /** The hand-added amount whose sheet (edit, delete) is open. */
    val contributionOptions: String? = null,
    val message: String? = null,
)

/** A fact line on a goal's page. */
@Immutable
public data class GoalLineUi(val text: String, val warning: Boolean)

/** What the goal page's controls report. */
public sealed interface GoalEvent {
    public data object AddMoneyClicked : GoalEvent
    public data object TakeOutClicked : GoalEvent
    public data class ContributionClicked(val id: String) : GoalEvent
    public data object EditContributionClicked : GoalEvent
    public data object DeleteContributionClicked : GoalEvent
    public data object ContributionOptionsDismissed : GoalEvent
    public data object OptionsClicked : GoalEvent
    public data object OptionsDismissed : GoalEvent
    public data object DeleteClicked : GoalEvent
    public data object DeleteConfirmed : GoalEvent
    public data object DeleteCancelled : GoalEvent
    public data class AmountChanged(val text: String) : GoalEvent
    public data class NoteChanged(val text: String) : GoalEvent
    public data object DateClicked : GoalEvent
    public data class DatePicked(val epochDay: Int) : GoalEvent
    public data object DatePickerDismissed : GoalEvent
    public data object EditorSaved : GoalEvent
    public data object EditorDismissed : GoalEvent
    public data object MessageShown : GoalEvent
}

/** Which of the form's pickers is open. */
public enum class GoalPicker { CATEGORY, TARGET_DATE, COUNT_FROM }

/** The new-goal and edit-goal form. */
@Immutable
public data class GoalEditorUiState(
    val loading: Boolean = true,
    val editingId: String? = null,
    val currency: String = "INR",
    val name: String = "",
    val targetText: String = "",
    val targetDate: Int? = null,
    val categoryId: String? = null,
    /** "Savings · Expense", or null for none. */
    val categoryLabel: String? = null,
    val countFrom: Int = 0,
    val categoryGroups: List<LfPickerGroup> = emptyList(),
    val openPicker: GoalPicker? = null,
    val error: String? = null,
    val saving: Boolean = false,
    /** Saved: the form closes. */
    val done: Boolean = false,
) {
    val isEdit: Boolean get() = editingId != null
}

public sealed interface GoalEditorEvent {
    public data class NameChanged(val text: String) : GoalEditorEvent
    public data class TargetChanged(val text: String) : GoalEditorEvent
    public data class PickerOpened(val picker: GoalPicker?) : GoalEditorEvent
    public data class TargetDatePicked(val epochDay: Int?) : GoalEditorEvent
    public data class CategoryPicked(val id: String?) : GoalEditorEvent
    public data class CountFromPicked(val epochDay: Int) : GoalEditorEvent
    public data object SaveClicked : GoalEditorEvent
}
