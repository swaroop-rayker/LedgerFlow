package com.ledgerflow.feature.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.statement.ApproveImportBatchUseCase
import com.ledgerflow.core.domain.statement.ImportBatches
import com.ledgerflow.core.domain.statement.ImportNote
import com.ledgerflow.core.domain.statement.ImportWaiting
import com.ledgerflow.core.domain.statement.ObserveImportBatchesUseCase
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ErasePendingUseCase
import com.ledgerflow.core.domain.usecase.MarkPendingAsTransactionUseCase
import com.ledgerflow.core.domain.usecase.ObserveInboxCountsUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.domain.usecase.RestorePendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.statement.FakeStatementImportRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **Nothing from an import reaches the ledger without the confirmed tap** (D8).
 *
 * The panel's action opens a dialog naming the count and each book's total;
 * only that dialog's Approve approves, and it approves exactly what it named.
 * The database half — one transaction, rollback, rows left alone — is
 * `ApproveImportBatchTest` on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxApproveImportTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val imports = FakeStatementImportRepository()
    private val ledger = FakeLedgerRepository()

    private fun imported(id: String, minor: Long, direction: ExtractedDirection, text: String) = PendingTransaction(
        id = id,
        source = EntrySource.IMPORT,
        extracted = ExtractedTransaction(
            amount = Money(minor),
            currency = "INR",
            direction = direction,
            merchantRaw = text,
            occurredAt = 1_791_000_000_000,
            confidence = 1.0,
        ),
        confidence = 1.0,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_791_000_000_000,
        reviewedAt = null,
        approvedEntryId = null,
    )

    /** One import of two rows, as the repository would report it. */
    private fun seedImport() {
        val rows = listOf(
            imported("a", 41_000, ExtractedDirection.DEBIT, "UPI-SWIGGY-628812345678"),
            imported("b", 8_500_000, ExtractedDirection.CREDIT, "NEFT CR-ACME LTD-SALARY"),
        )
        rows.forEach(pending::put)
        val waiting = ImportWaiting("INR", rows)
        imports.waiting["fp"] = waiting
        imports.batches.value = listOfNotNull(ImportBatches.summarise("fp", ImportNote("oct.csv", 1L), waiting))
    }

    private fun TestScope.viewModel(): InboxViewModel {
        val approvePending = ApprovePendingUseCase(
            pending,
            merchants,
            ApproveTransactionUseCase(ledger, NoOpBudgetAlertTrigger),
        )
        val vm = InboxViewModel(
            observePending = ObservePendingUseCase(pending),
            observeCounts = ObserveInboxCountsUseCase(pending),
            approvePending = approvePending,
            discardPending = DiscardPendingUseCase(pending),
            restorePending = RestorePendingUseCase(pending),
            erasePending = ErasePendingUseCase(pending),
            markAsTransaction = MarkPendingAsTransactionUseCase(pending),
            importBatches = ImportBatchActions(
                observe = ObserveImportBatchesUseCase(imports),
                approveAll = ApproveImportBatchUseCase(
                    imports,
                    approvePending,
                    merchants,
                    FakeCategoryRepository(),
                ),
            ),
        )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    @Test
    fun theQueueShowsThePanel_otherFiltersDoNot() = runTest(dispatcher) {
        seedImport()
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.visibleImportBatches.single().ready).isEqualTo(2)

        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.SUPPRESSED))
        advanceUntilIdle()
        assertThat(vm.state.value.visibleImportBatches).isEmpty()
    }

    @Test
    fun thePanelsAction_opensTheConfirmation_andApprovesNothing() = runTest(dispatcher) {
        seedImport()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(InboxEvent.ApproveImportRequested("fp"))
        advanceUntilIdle()

        val confirmation = vm.state.value.confirmation as InboxConfirmation.ApproveImport
        assertThat(confirmation.batch.spent.total).isEqualTo(Money(41_000))
        assertThat(confirmation.batch.received.total).isEqualTo(Money(8_500_000))
        assertThat(ledger.approved).isEmpty()

        vm.onEvent(InboxEvent.ApproveImportDismissed)
        advanceUntilIdle()
        assertThat(vm.state.value.confirmation).isNull()
        assertThat(ledger.approved).isEmpty()
    }

    /** The confirmed tap: each row in its own book, the text as its note, no merchant made. */
    @Test
    fun confirming_approvesEachRowInItsBook_withoutCreatingAMerchant() = runTest(dispatcher) {
        seedImport()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(InboxEvent.ApproveImportRequested("fp"))
        vm.onEvent(InboxEvent.ApproveImportConfirmed)
        advanceUntilIdle()

        assertThat(ledger.approved.map { it.ledger to it.amount }).containsExactly(
            LedgerType.DEBIT to Money(41_000),
            LedgerType.CREDIT to Money(8_500_000),
        )
        assertThat(ledger.approved.map { it.note })
            .containsExactly("UPI-SWIGGY-628812345678", "NEFT CR-ACME LTD-SALARY")
        assertThat(ledger.approved.map { it.assignment.merchantId }.toSet()).containsExactly(null)
        assertThat(merchants.created).isEmpty()
        assertThat(vm.state.value.message).isEqualTo("2 added to your ledger.")
    }

    /** A row was reviewed meanwhile: the dialog's count no longer holds, so nothing is approved. */
    @Test
    fun aBatchThatChangedSinceTheDialog_approvesNothing() = runTest(dispatcher) {
        seedImport()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(InboxEvent.ApproveImportRequested("fp"))
        advanceUntilIdle()

        imports.waiting["fp"] = ImportWaiting("INR", listOf(requireNotNull(pending.get("a"))))
        vm.onEvent(InboxEvent.ApproveImportConfirmed)
        advanceUntilIdle()

        assertThat(ledger.approved).isEmpty()
        assertThat(vm.state.value.message).startsWith("Nothing approved")
    }
}
