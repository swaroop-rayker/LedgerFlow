package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The review screen shows the message a candidate came from (BUG58, owner
 * 2026-10-21).
 *
 * The owner's ghosts read "From a notification · nothing was extracted" and
 * nothing else, so neither they nor anyone else could say what each one was.
 * The text is what turns a ghost into something a user can act on, and into a
 * fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewShowsCapturedMessageTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val ledger = FakeLedgerRepository()
    private val merchants = FakeMerchantRepository()

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
        paymentMethods = FakePaymentMethodRepository(),
        ledgerRepository = ledger,
        ids = Uuid7Generator(SecureRandom()),
    )

    @Test
    fun aRetainedMessage_isShown() = runTest(dispatcher) {
        pending.put(ghost)
        pending.capturedTexts["p1"] = "Your order has been shipped"

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.capturedText).isEqualTo("Your order has been shipped")
    }

    @Test
    fun aMessagePastRetention_showsNothing() = runTest(dispatcher) {
        pending.put(ghost)

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.capturedText).isNull()
    }

    /** The text is a fact about the message, never typing: opening writes no draft. */
    @Test
    fun showingTheMessage_writesNoDraft() = runTest(dispatcher) {
        pending.put(ghost)
        pending.capturedTexts["p1"] = "Your order has been shipped"

        viewModel()
        advanceUntilIdle()
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        assertThat(pending.get("p1")?.edits).isNull()
    }

    private companion object {
        const val PAST_DEBOUNCE = 400L
    }
}
