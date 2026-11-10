package com.ledgerflow.feature.tools.recurring

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.usecase.RecurringSuggestion
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import org.junit.Test

/** How recurring items read (V2-7). A wrong word here is a wrong date or a wrong promise. */
class RecurringWordsTest {

    private val due = LocalDate.of(2026, 12, 1).toEpochDay().toInt()

    private fun rent(
        toInbox: Boolean = true,
        remind: Int = 1,
        varies: Boolean = false,
        paused: Boolean = false,
        cadence: Cadence = Cadence.MONTHLY,
        everyDays: Int? = null,
    ) = RecurringItem(
        id = "rent", name = "Rent", ledger = LedgerType.DEBIT, amount = Money(25_000_00), amountVaries = varies,
        merchantId = null, categoryId = null, subcategoryId = null, cadence = cadence, everyDays = everyDays,
        anchorDate = due, nextDue = due, remindDaysBefore = remind, toInbox = toInbox, paused = paused,
    )

    @Test
    fun caption_saysTheScheduleTheReminderAndTheInbox() {
        assertThat(RecurringWords.caption(rent())).isEqualTo("Monthly · due 1 Dec · reminds 1 day before · to Inbox")
        assertThat(RecurringWords.caption(rent(toInbox = false, varies = true)))
            .isEqualTo("Monthly · due 1 Dec · amount varies · reminder only")
        assertThat(RecurringWords.caption(rent(paused = true))).isEqualTo("Paused · Monthly")
        assertThat(RecurringWords.caption(rent(cadence = Cadence.EVERY_N_DAYS, everyDays = 10, remind = 0)))
            .isEqualTo("Every 10 days · due 1 Dec · reminds on the day · to Inbox")
    }

    @Test
    fun amount_ofOneThatVaries_isApproximate() {
        assertThat(RecurringWords.amount(rent(), "INR")).isEqualTo("₹25,000.00")
        assertThat(RecurringWords.amount(rent(varies = true), "INR")).isEqualTo("~₹25,000.00")
    }

    /** The Inbox promise names the day the window closes: two after the due date. */
    @Test
    fun facts_nameTheDayAnInboxCandidateMayArrive() {
        assertThat(RecurringWords.facts(rent(), listOf("Landlord", "Housing"))).containsExactly(
            "Next due 1 Dec 2026",
            "Reminds the day before",
            "Adds to the Inbox if nothing is captured by 3 Dec",
            "Landlord · Housing",
        ).inOrder()
        assertThat(RecurringWords.facts(rent(toInbox = false), emptyList()))
            .contains("Reminds only; never adds to the Inbox")
    }

    @Test
    fun outcome_saysWhatEachDueDateTurnedInto() {
        fun of(outcome: OccurrenceOutcome, entry: String? = null, reminded: Long? = null) =
            RecurringWords.outcome(RecurringOccurrence("rent", due, outcome, null, entry, reminded))

        assertThat(of(OccurrenceOutcome.MATCHED, entry = "e-1")).isEqualTo("captured")
        assertThat(of(OccurrenceOutcome.MATCHED)).isEqualTo("captured, waiting in the Inbox")
        assertThat(of(OccurrenceOutcome.CANDIDATE)).isEqualTo("nothing captured, added to the Inbox")
        assertThat(of(OccurrenceOutcome.SKIPPED)).isEqualTo("nothing captured")
        assertThat(of(OccurrenceOutcome.WAITING, reminded = 1L)).isEqualTo("reminded, waiting")
    }

    @Test
    fun suggestion_isAPatternAndAForecast() {
        val offer = RecurringSuggestion(
            merchantId = "m", name = "Netflix", ledger = LedgerType.DEBIT, amount = Money(649_00),
            cadence = Cadence.MONTHLY, everyDays = null, occurrences = 4,
            nextExpected = LocalDate.of(2026, 11, 14).toEpochDay().toInt(),
        )

        assertThat(RecurringWords.suggestion(offer)).isEqualTo("Every month, 4 times · next about 14 Nov")
    }
}
