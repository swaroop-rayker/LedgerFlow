package com.ledgerflow.feature.tools.recurring

import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.picker.LfPickerOption

/** The owner's mockups of 2026-11-09 as states, for the previews and the goldens. */
internal object RecurringPreviews {

    val list = RecurringListUiState(
        loading = false,
        suggestions = listOf(
            SuggestionRowUi(
                "m-netflix", LedgerType.DEBIT, "Netflix", "₹649.00",
                "Every month, 4 times · next about 14 Nov",
            ),
        ),
        expense = listOf(
            RecurringRowUi(
                "r-rent", "Rent", "₹25,000.00",
                "Monthly · due 1 Dec · reminds 1 day before · to Inbox", paused = false,
            ),
            RecurringRowUi(
                "r-power", "Electricity", "~₹2,100.00",
                "Monthly · due 18 Nov · amount varies · reminder only", paused = false,
            ),
            RecurringRowUi("r-gym", "Gym", "₹1,500.00", "Paused · Monthly", paused = true),
        ),
        income = listOf(
            RecurringRowUi(
                "r-salary", "Salary", "₹1,20,000.00",
                "Monthly · due 30 Nov · reminds on the day · to Inbox", paused = false,
            ),
        ),
    )

    val empty = RecurringListUiState(loading = false)

    val item = RecurringItemUiState(
        loading = false,
        name = "Rent",
        amount = "₹25,000.00",
        subtitle = "Expense · Monthly",
        facts = listOf(
            "Next due 1 Dec 2026",
            "Reminds the day before",
            "Adds to the Inbox if nothing is captured by 3 Dec",
            "Landlord · Housing",
        ),
        recent = listOf(
            OccurrenceRowUi("1 Nov", "captured"),
            OccurrenceRowUi("1 Oct", "nothing captured, added to the Inbox"),
            OccurrenceRowUi("1 Sep", "nothing captured"),
        ),
    )

    val editor = RecurringEditorUiState(
        loading = false,
        name = "Rent",
        amountText = "25,000",
        cadence = Cadence.MONTHLY,
        firstDue = DAY_1_DEC_2026,
        merchantId = "m-landlord",
        merchantName = "Landlord",
        categoryChoice = "c-housing",
        categoryLabel = "Housing",
        merchantOptions = listOf(LfPickerOption("m-landlord", "Landlord")),
        categoryOptions = listOf(LfPickerOption("c-housing", "Housing")),
    )

    /** Every N days, amount varies, reminder only: every optional control showing. */
    val editorEveryNDays = editor.copy(
        name = "Water can",
        cadence = Cadence.EVERY_N_DAYS,
        everyDaysText = "10",
        amountVaries = true,
        toInbox = false,
        remindDaysBefore = 0,
    )

    private const val DAY_1_DEC_2026 = 20788
}
