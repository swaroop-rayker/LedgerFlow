package com.ledgerflow.feature.tools.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.usecase.GetRecurringSuggestionsUseCase
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.recurring.FakeRecurringRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The recurring form (V2-7; mockup 3): what it saves, that saving asks for a
 * pass, and that "Add" on a suggestion arrives filled from detection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecurringEditorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val recurring = FakeRecurringRepository()
    private val analytics = FakeAnalyticsRepository()
    private val merchants = FakeMerchantRepository()
    private val categories = FakeCategoryRepository()
    private var passes = 0

    private val today = LocalDate.of(2026, 11, 10).toEpochDay().toInt()
    private val clock = Clock {
        LocalDate.ofEpochDay(today.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        merchants.merchants.value = listOf(Merchant("landlord", "Landlord", "landlord", null, null))
        val housing = category("housing", null, "Housing")
        categories.trees[LedgerType.DEBIT] = listOf(CategoryTree(housing, listOf(category("rent", "housing", "Rent"))))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(args: Map<String, String> = emptyMap()) = RecurringEditorViewModel(
        recurring = recurring,
        suggestions = GetRecurringSuggestionsUseCase(analytics, recurring, clock),
        merchants = merchants,
        categories = categories,
        ledger = FakeLedgerRepository(),
        trigger = { passes++ },
        clock = clock,
        savedStateHandle = SavedStateHandle(args),
    )

    @Test
    fun aNewItem_savesWhatTheFormSays_andAsksForAPass() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RecurringEditorEvent.NameChanged("Rent"))
        vm.onEvent(RecurringEditorEvent.AmountChanged("25,000"))
        vm.onEvent(RecurringEditorEvent.MerchantPicked("landlord"))
        vm.onEvent(RecurringEditorEvent.CategoryPicked("rent"))
        vm.onEvent(RecurringEditorEvent.RemindPicked(3))
        vm.onEvent(RecurringEditorEvent.ToInboxToggled)

        vm.onEvent(RecurringEditorEvent.SaveClicked)

        val saved = recurring.items.value.single()
        assertThat(saved.amount).isEqualTo(Money(25_000_00))
        assertThat(saved.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(saved.merchantId).isEqualTo("landlord")
        // A subcategory saves as the pair the ledger uses.
        assertThat(saved.categoryId).isEqualTo("housing")
        assertThat(saved.subcategoryId).isEqualTo("rent")
        assertThat(saved.remindDaysBefore).isEqualTo(3)
        assertThat(saved.toInbox).isFalse()
        assertThat(saved.nextDue).isEqualTo(today)
        assertThat(passes).isEqualTo(1)
        assertThat(vm.state.value.done).isTrue()
    }

    @Test
    fun everyNDays_savesItsDays_andOtherCadencesDoNot() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RecurringEditorEvent.NameChanged("Water can"))
        vm.onEvent(RecurringEditorEvent.AmountChanged("90"))
        vm.onEvent(RecurringEditorEvent.CadencePicked(Cadence.EVERY_N_DAYS))
        vm.onEvent(RecurringEditorEvent.EveryDaysChanged("10"))

        vm.onEvent(RecurringEditorEvent.SaveClicked)

        assertThat(recurring.items.value.single().everyDays).isEqualTo(10)
    }

    /** Changing the book clears a category, which belongs to one book. */
    @Test
    fun changingTheBook_clearsTheCategory() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(RecurringEditorEvent.CategoryPicked("housing"))

        vm.onEvent(RecurringEditorEvent.LedgerPicked(LedgerType.CREDIT))

        assertThat(vm.state.value.categoryChoice).isNull()
        assertThat(vm.state.value.ledger).isEqualTo(LedgerType.CREDIT)
    }

    @Test
    fun addOnASuggestion_arrivesFilledFromDetection() = runTest(dispatcher) {
        analytics.recurring[LedgerType.DEBIT] = listOf(
            RecurringMerchant(
                merchantId = "landlord", name = "Landlord", occurrences = 5, intervalDays = 30, regularity = 0.02,
                typicalAmount = Money(25_000_00), lastSeen = today - 9, nextExpected = today + 21,
            ),
        )

        val vm = viewModel(
            mapOf(
                RecurringEditorViewModel.FROM_MERCHANT_ARG to "landlord",
                RecurringEditorViewModel.FROM_LEDGER_ARG to LedgerType.DEBIT.name,
            ),
        )

        val form = vm.state.value
        assertThat(form.name).isEqualTo("Landlord")
        assertThat(form.amountText).isEqualTo("25,000.00")
        assertThat(form.cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(form.firstDue).isEqualTo(today + 21)
        assertThat(form.merchantId).isEqualTo("landlord")
        assertThat(recurring.items.value).isEmpty()
    }

    private fun category(id: String, parent: String?, name: String) = Category(
        id = id, parentId = parent, ledger = LedgerType.DEBIT, name = name, icon = "", colorArgb = 0,
        sortOrder = 0, isSystem = false,
    )
}
