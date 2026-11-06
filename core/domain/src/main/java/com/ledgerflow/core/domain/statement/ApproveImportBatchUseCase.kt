package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.inbox.InboxError
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.usecase.ApprovalEdits
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.InboxException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The Inbox's "Approve all" panels: each import with rows still waiting (D8). */
public class ObserveImportBatchesUseCase @Inject constructor(private val repository: StatementImportRepository) {
    public operator fun invoke(): Flow<List<ImportBatch>> = repository.observeBatches()
}

/** What "Approve all" came to. Only [Approved] changed anything. */
public sealed interface ImportApproval {

    /** Every ready row is in the ledger. */
    public data class Approved(val count: Int) : ImportApproval

    /**
     * The rows ready are no longer the [confirmed] ones the user agreed to
     * (one was reviewed, discarded or edited meanwhile). Nothing was approved;
     * the panel shows the new count to confirm again.
     */
    public data class Changed(val confirmed: Int, val readyNow: Int) : ImportApproval

    /** A row was refused, so nothing was approved: one transaction, all or nothing. */
    public data class Refused(val error: InboxError) : ImportApproval
}

/**
 * "Approve all" for one import (V2-4, decision D8, owner-confirmed 2026-11-05).
 *
 * **The confirmed tap is the human act Law 1 requires**, made once for a batch
 * the user has seen counted and totalled per book. D8's five rules, and where
 * each one holds:
 *
 * 1. *Import writes candidates only, through the normal dedupe* — the import
 *    itself (`ImportStatementUseCase`); nothing here writes a candidate.
 * 2. *One import batch, never SMS or notification candidates* — the rows are
 *    read by the import's own reference prefix, and [ImportBatches.isReady]
 *    takes an imported candidate only.
 * 3. *The confirmation names the count and each book's total separately* —
 *    [ImportBatch], and [confirmed] here: if the ready rows are no longer that
 *    many, nothing is approved and the user is asked again.
 * 4. *Only complete rows qualify* — [ImportBatches.isReady]; the rest stay in
 *    the Inbox for one-by-one review.
 * 5. *Every row through `ApproveTransactionUseCase`, as one transaction* —
 *    each row goes through [ApprovePendingUseCase], which is the Inbox's one
 *    path to it, inside [StatementImportRepository.allOrNothing].
 *
 * **No merchant is created** (owner, 2026-11-06): a row takes an existing
 * merchant whose name or learned alias matches the text read, with that
 * merchant's default category when it belongs to the row's book; otherwise
 * none. The text read is kept as the entry's note either way.
 */
public class ApproveImportBatchUseCase @Inject constructor(
    private val repository: StatementImportRepository,
    private val approvePending: ApprovePendingUseCase,
    private val merchants: MerchantRepository,
    private val categories: CategoryRepository,
) {

    /** @param confirmed how many rows the confirmation named. */
    public suspend operator fun invoke(fingerprint: String, confirmed: Int): ImportApproval {
        val waiting = repository.waitingIn(fingerprint)
        val ready = waiting.rows.filter { ImportBatches.isReady(it, waiting.baseCurrency) }
        if (ready.size != confirmed) return ImportApproval.Changed(confirmed, ready.size)

        val result = repository.allOrNothing {
            ready.firstNotNullOfOrNull { row -> approvePending(row.id, editsFor(row)).exceptionOrNull() }
                ?.let { Result.failure(it) }
                ?: Result.success(ready.size)
        }
        return result.fold(
            onSuccess = { ImportApproval.Approved(it) },
            onFailure = { refusal ->
                ImportApproval.Refused((refusal as? InboxException)?.error ?: InboxError.Rejected(refusal.toString()))
            },
        )
    }

    private suspend fun editsFor(row: PendingTransaction): ApprovalEdits {
        val text = row.extracted.merchantRaw?.trim()?.takeIf { it.isNotEmpty() }
        val merchant = text?.let { merchants.findByName(it) }
        val book = row.extracted.direction.toLedgerOrNull()
        val category = merchant?.defaultCategoryId?.let { categories.find(it) }?.takeIf { it.ledger == book }
        return ApprovalEdits(
            merchantId = merchant?.id,
            categoryId = category?.let { it.parentId ?: it.id },
            subcategoryId = category?.takeIf { it.parentId != null }?.id,
            note = text,
            createMerchant = false,
        )
    }
}

