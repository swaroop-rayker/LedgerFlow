package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The review screen's merchant picker browses by merchant category (V2-6 PR 3):
 * it reads the categories, and files nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewMerchantPickerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val ledger = FakeLedgerRepository()
    private val merchants = FakeMerchantRepository()
    private val merchantCategories = FakeMerchantCategoryRepository()

    private val ghost = PendingTransaction(
        id = "p1",
        source = EntrySource.NOTIFICATION,
        extracted = ExtractedTransaction(),
        confidence = 0.0,
        status = PendingStatus.PENDING,
        needsManualFill = true,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
    )

    private fun viewModel() = ReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ReviewViewModel.PENDING_ID_ARG to "p1")),
        approvePending = ApprovePendingUseCase(
            pending,
            merchants,
            ApproveTransactionUseCase(ledger, NoOpBudgetAlertTrigger),
        ),
        discardPending = DiscardPendingUseCase(pending),
        pendingRepository = pending,
        observeCategoryTree = ObserveCategoryTreeUseCase(FakeCategoryRepository()),
        merchants = merchants,
        merchantCategories = merchantCategories,
        paymentMethods = FakePaymentMethodRepository(),
        ledgerRepository = ledger,
        ids = Uuid7Generator(SecureRandom()),
    )

    @Test
    fun thePicker_readsTheMerchantCategories() = runTest(dispatcher) {
        pending.put(ghost)
        val fuel = (merchantCategories.create("Fuel") as TaxonomyResult.Success<MerchantCategory>).value

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.merchantCategories).containsExactly(fuel)
    }

    /** Opening and closing the picker, and choosing a merchant, never files one (V2-PLAN §5.2). */
    @Test
    fun choosingAMerchant_filesNothing() = runTest(dispatcher) {
        pending.put(ghost)
        merchantCategories.create("Fuel")
        merchants.merchants.value = listOf(Merchant("shell", "Shell", "shell", null, null))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(ReviewEvent.PickerOpened(ReviewPicker.Merchant))
        vm.onEvent(ReviewEvent.PickerItemSelected("shell"))
        advanceUntilIdle()

        assertThat(vm.state.value.merchantId).isEqualTo("shell")
        assertThat(merchantCategories.filed).isEmpty()
    }
}
