package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * A merchant that looks recurring, offered as an item the user confirms
 * (V2-PLAN §4.2: "seeded from the existing recurring detection as suggestions
 * the user confirms"). Never added by itself.
 */
public data class RecurringSuggestion(
    val merchantId: String,
    val name: String,
    val ledger: LedgerType,
    /** The median of what was paid: a figure that really appeared. */
    val amount: Money,
    val cadence: Cadence,
    /** Set only for [Cadence.EVERY_N_DAYS]. */
    val everyDays: Int?,
    val occurrences: Int,
    /** Days since epoch: the detection's forecast, a projection. */
    val nextExpected: Int,
)

/**
 * The Recurring page's suggestions (V2-7; the owner's mockup 1): both books'
 * detected recurring merchants, less those an item already names in that
 * book and those the user said are not recurring. Each book read separately
 * (Law 2); the two lists are only placed side by side.
 */
public class GetRecurringSuggestionsUseCase @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val recurring: RecurringRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(): List<RecurringSuggestion> {
        val today = LocalDates.of(clock.nowMillis())
        val items = recurring.observeItems().first()
        val dismissed = recurring.observeDismissed().first()
        return LedgerType.entries.flatMap { ledger ->
            val named = items.filter { it.ledger == ledger }.mapNotNull { it.merchantId }.toSet()
            analytics.recurringMerchants(ledger, today)
                .filter { it.merchantId !in named && (it.merchantId to ledger) !in dismissed }
                .map { it.toSuggestion(ledger) }
        }
    }

    private fun RecurringMerchant.toSuggestion(ledger: LedgerType): RecurringSuggestion {
        val cadence = cadenceOf(intervalDays)
        return RecurringSuggestion(
            merchantId = merchantId,
            name = name,
            ledger = ledger,
            amount = typicalAmount,
            cadence = cadence,
            everyDays = intervalDays.takeIf { cadence == Cadence.EVERY_N_DAYS },
            occurrences = occurrences,
            nextExpected = nextExpected,
        )
    }

    public companion object {
        /**
         * The cadence a mean interval most plausibly is: a week, a month
         * (a calendar month is 28 to 31 days, and payments drift a day or two
         * either side), a quarter or a year; anything else is every N days.
         */
        public fun cadenceOf(intervalDays: Int): Cadence = when (intervalDays) {
            in WEEK -> Cadence.WEEKLY
            in MONTH -> Cadence.MONTHLY
            in QUARTER -> Cadence.QUARTERLY
            in YEAR -> Cadence.YEARLY
            else -> Cadence.EVERY_N_DAYS
        }

        private val WEEK = 6..8
        private val MONTH = 26..34
        private val QUARTER = 84..98
        private val YEAR = 350..380
    }
}
