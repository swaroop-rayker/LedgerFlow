package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **CHANGE#1: the book comes from the message, not from the user.**
 *
 * A bank SMS reading "debited" is spend and "credited" is income, and the parser
 * has read that into `direction` before the review screen opens. Asking the user
 * to pick again was confusing, so the control is gone — derived silently
 * instead.
 *
 * The exception these tests pin is the one §5.1 forces. The never-drop rule
 * means a message no rule understood still reaches the Inbox with no direction
 * at all; there is nothing to derive from, so *those* candidates get a Book row
 * and no others. Without [ReviewUiState.bookIsUnread] they would be unapprovable
 * — a financial SMS the user can see and cannot act on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewBookDerivationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val paymentMethods = FakePaymentMethodRepository()
    private val categories = FakeCategoryRepository()
    private val ledger = FakeLedgerRepository()

    private fun candidate(direction: ExtractedDirection) = PendingTransaction(
        id = "p1",
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(200L).takeIf { direction != ExtractedDirection.UNKNOWN },
            direction = direction,
            merchantRaw = "RAMESH KUMAR".takeIf { direction != ExtractedDirection.UNKNOWN },
            confidence = if (direction == ExtractedDirection.UNKNOWN) 0.0 else 0.9,
        ),
        confidence = if (direction == ExtractedDirection.UNKNOWN) 0.0 else 0.9,
        status = PendingStatus.PENDING,
        needsManualFill = direction == ExtractedDirection.UNKNOWN,
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
        observeCategoryTree = ObserveCategoryTreeUseCase(categories),
        merchants = merchants,
        paymentMethods = paymentMethods,
        ledgerRepository = ledger,
        ids = Uuid7Generator(SecureRandom()),
    )

    @Test
    fun aDebitedMessage_filesAsAnExpense_withNoBookToChoose() = runTest(dispatcher) {
        pending.put(candidate(ExtractedDirection.DEBIT))

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(subject.state.value.bookIsUnread).isFalse()
    }

    @Test
    fun aCreditedMessage_filesAsIncome_withNoBookToChoose() = runTest(dispatcher) {
        pending.put(candidate(ExtractedDirection.CREDIT))

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.ledger).isEqualTo(LedgerType.CREDIT)
        assertThat(subject.state.value.bookIsUnread).isFalse()
    }

    /**
     * §5.1's never-drop row is the only one that asks.
     *
     * It arrives with no book and cannot be approved until the user supplies
     * one — Law 2's ledgers never meet to correct a wrong guess, so this is the
     * one place the question is worth asking.
     */
    @Test
    fun aMessageWithNoDirection_asksForTheBookAndBlocksApprovalUntilAnswered() =
        runTest(dispatcher) {
            pending.put(candidate(ExtractedDirection.UNKNOWN))

            val subject = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(subject.state.value.ledger).isNull()
            assertThat(subject.state.value.bookIsUnread).isTrue()
            assertThat(subject.state.value.canApprove).isFalse()

            subject.onEvent(ReviewEvent.PickerOpened(ReviewPicker.Book))
            subject.onEvent(ReviewEvent.PickerItemSelected(LedgerType.CREDIT.name))
            subject.onEvent(ReviewEvent.AmountChanged("500"))
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(subject.state.value.ledger).isEqualTo(LedgerType.CREDIT)
            assertThat(subject.state.value.canApprove).isTrue()
        }

    /**
     * The payee the message carried is offered without being created.
     *
     * §5.1's `createOrGet` runs at approval (P2-4's decision), so a candidate
     * the user discards leaves no merchant behind — the row shows the raw name
     * and the taxonomy stays untouched until someone agrees it is real.
     */
    @Test
    fun theMessagesPayeeIsShown_withoutCreatingAMerchant() = runTest(dispatcher) {
        pending.put(candidate(ExtractedDirection.DEBIT))

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.selectedMerchant).isEqualTo("RAMESH KUMAR")
        assertThat(subject.state.value.merchantId).isNull()
        assertThat(merchants.created).isEmpty()
    }
}
