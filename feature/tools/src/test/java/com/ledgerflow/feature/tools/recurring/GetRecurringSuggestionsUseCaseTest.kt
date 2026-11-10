package com.ledgerflow.feature.tools.recurring

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.RecurringDetails
import com.ledgerflow.core.domain.usecase.GetRecurringSuggestionsUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.recurring.FakeRecurringRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The Recurring page's suggestions (V2-PLAN §4.2: "seeded from the existing
 * recurring detection as suggestions the user confirms"): each book's detected
 * merchants, less those an item names in that book and those dismissed.
 */
class GetRecurringSuggestionsUseCaseTest {

    private val analytics = FakeAnalyticsRepository()
    private val recurring = FakeRecurringRepository()
    private val suggest = GetRecurringSuggestionsUseCase(analytics, recurring, Clock { 0L })

    @Test
    fun cadence_isTheNearestPlainOne_orEveryNDays() {
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(7)).isEqualTo(Cadence.WEEKLY)
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(30)).isEqualTo(Cadence.MONTHLY)
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(28)).isEqualTo(Cadence.MONTHLY)
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(91)).isEqualTo(Cadence.QUARTERLY)
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(365)).isEqualTo(Cadence.YEARLY)
        assertThat(GetRecurringSuggestionsUseCase.cadenceOf(14)).isEqualTo(Cadence.EVERY_N_DAYS)
    }

    @Test
    fun bothBooks_areSuggested_withTheirCadence() = runBlocking<Unit> {
        analytics.recurring[LedgerType.DEBIT] = listOf(detected("netflix", interval = 30))
        analytics.recurring[LedgerType.CREDIT] = listOf(detected("employer", interval = 31))

        val offers = suggest()

        assertThat(offers.map { it.merchantId to it.ledger }).containsExactly(
            "netflix" to LedgerType.DEBIT,
            "employer" to LedgerType.CREDIT,
        )
        assertThat(offers.first().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(offers.first().everyDays).isNull()
    }

    @Test
    fun aMerchantWithAnItemInThatBook_isNotSuggested_butInTheOtherBookItIs() = runBlocking<Unit> {
        recurring.create(details(merchantId = "shop", ledger = LedgerType.DEBIT))
        analytics.recurring[LedgerType.DEBIT] = listOf(detected("shop", interval = 30))
        analytics.recurring[LedgerType.CREDIT] = listOf(detected("shop", interval = 30))

        assertThat(suggest().map { it.ledger }).containsExactly(LedgerType.CREDIT)
    }

    @Test
    fun aDismissedSuggestion_staysDismissed_inItsBook() = runBlocking<Unit> {
        analytics.recurring[LedgerType.DEBIT] = listOf(detected("gym", interval = 14))
        recurring.dismiss("gym", LedgerType.DEBIT)

        assertThat(suggest()).isEmpty()
    }

    @Test
    fun anOddInterval_carriesItsDays() = runBlocking<Unit> {
        analytics.recurring[LedgerType.DEBIT] = listOf(detected("water", interval = 10))

        val offer = suggest().single()

        assertThat(offer.cadence).isEqualTo(Cadence.EVERY_N_DAYS)
        assertThat(offer.everyDays).isEqualTo(10)
    }

    private fun detected(merchantId: String, interval: Int) = RecurringMerchant(
        merchantId = merchantId, name = merchantId, occurrences = 4, intervalDays = interval, regularity = 0.05,
        typicalAmount = Money(649_00), lastSeen = 100, nextExpected = 100 + interval,
    )

    private fun details(merchantId: String, ledger: LedgerType) = RecurringDetails(
        name = merchantId, ledger = ledger, amount = Money(649_00), amountVaries = false, merchantId = merchantId,
        categoryId = null, subcategoryId = null, cadence = Cadence.MONTHLY, everyDays = null, firstDue = 130,
        remindDaysBefore = 1, toInbox = true,
    )
}
