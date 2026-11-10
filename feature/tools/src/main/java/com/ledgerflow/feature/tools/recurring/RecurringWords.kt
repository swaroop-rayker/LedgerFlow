package com.ledgerflow.feature.tools.recurring

import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.recurring.RecurringSchedule
import com.ledgerflow.core.domain.usecase.RecurringSuggestion
import com.ledgerflow.core.model.LedgerType
import java.time.LocalDate

/**
 * How recurring items read (V2-7; the owner's mockups of 2026-11-09). One
 * place, so the list, the item's page and the form agree, and unit-tested,
 * because a wrong word here is a wrong date or a wrong promise.
 */
internal object RecurringWords {

    fun day(epochDay: Int): String = DisplayDates.dayMonth(LocalDate.ofEpochDay(epochDay.toLong()))

    fun date(epochDay: Int): String = DisplayDates.dayMonthYear(LocalDate.ofEpochDay(epochDay.toLong()))

    fun book(ledger: LedgerType): String = when (ledger) {
        LedgerType.DEBIT -> "Expense"
        LedgerType.CREDIT -> "Income"
    }

    /** The form's chip, and the first word of a caption. */
    fun cadence(cadence: Cadence, everyDays: Int?): String = when (cadence) {
        Cadence.WEEKLY -> "Weekly"
        Cadence.MONTHLY -> "Monthly"
        Cadence.QUARTERLY -> "Quarterly"
        Cadence.YEARLY -> "Yearly"
        Cadence.EVERY_N_DAYS -> if (everyDays == null) "Every N days" else "Every $everyDays days"
    }

    /** "₹25,000.00"; "~₹2,100.00" when the amount varies. */
    fun amount(item: RecurringItem, currency: String): String {
        val figure = MoneyFormat.symbolised(item.amount.minor, currency)
        return if (item.amountVaries) "~$figure" else figure
    }

    fun reminds(daysBefore: Int): String = when (daysBefore) {
        0 -> "reminds on the day"
        1 -> "reminds 1 day before"
        else -> "reminds $daysBefore days before"
    }

    /** The list row's line: "Monthly · due 1 Dec · reminds 1 day before · to Inbox". */
    fun caption(item: RecurringItem): String {
        if (item.paused) return "Paused · ${cadence(item.cadence, item.everyDays)}"
        return listOfNotNull(
            cadence(item.cadence, item.everyDays),
            "due ${day(item.nextDue)}",
            "amount varies".takeIf { item.amountVaries },
            if (item.toInbox) reminds(item.remindDaysBefore) + " · to Inbox" else "reminder only",
        ).joinToString(" · ")
    }

    /** The item page's lines, under its amount. [names] are the merchant and category, when set. */
    fun facts(item: RecurringItem, names: List<String>): List<String> = listOfNotNull(
        if (item.paused) "Paused" else "Next due ${date(item.nextDue)}",
        when (item.remindDaysBefore) {
            0 -> "Reminds on the day"
            1 -> "Reminds the day before"
            else -> "Reminds ${item.remindDaysBefore} days before"
        },
        if (item.toInbox) {
            "Adds to the Inbox if nothing is captured by ${day(RecurringSchedule.windowClosesAfter(item.nextDue))}"
        } else {
            "Reminds only; never adds to the Inbox"
        },
        names.takeIf { it.isNotEmpty() }?.joinToString(" · "),
    )

    /** What one due date turned into, for "Recent". */
    fun outcome(occurrence: RecurringOccurrence): String = when (occurrence.outcome) {
        OccurrenceOutcome.MATCHED ->
            if (occurrence.entryId != null) "captured" else "captured, waiting in the Inbox"
        OccurrenceOutcome.CANDIDATE -> "nothing captured, added to the Inbox"
        OccurrenceOutcome.SKIPPED -> "nothing captured"
        OccurrenceOutcome.WAITING -> if (occurrence.remindedAt != null) "reminded, waiting" else "waiting"
    }

    /** "Every month, 4 times · next about 14 Nov": a pattern and a forecast, said as one. */
    fun suggestion(suggestion: RecurringSuggestion): String {
        val every = when (suggestion.cadence) {
            Cadence.WEEKLY -> "Every week"
            Cadence.MONTHLY -> "Every month"
            Cadence.QUARTERLY -> "Every 3 months"
            Cadence.YEARLY -> "Every year"
            Cadence.EVERY_N_DAYS -> "Every ${suggestion.everyDays} days"
        }
        return "$every, ${suggestion.occurrences} times · next about ${day(suggestion.nextExpected)}"
    }
}
