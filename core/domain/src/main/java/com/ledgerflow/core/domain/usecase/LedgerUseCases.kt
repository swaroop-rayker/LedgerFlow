package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.LedgerEntry
import javax.inject.Inject

/**
 * **The only thing in LedgerFlow that may insert into `ledger_entry`** (Law 1).
 *
 * Parsers, workers and receivers write to `pending_transaction` and stop there;
 * an entry becomes real when a human taps a button, and this is that tap. The
 * law is about *automated* sources being unable to commit, which is why manual
 * entry calls this directly with `source = MANUAL` and `source_ref_id = NULL`
 * rather than round-tripping through a review queue the user would leave in the
 * same gesture (§5.4).
 *
 * It is deliberately thin. The validation §6.1.1 assigns to "the approval path"
 * — a subcategory's parent equalling its category, a category belonging to the
 * book it was filed under — has to run inside the transaction that writes, or a
 * concurrent soft-delete slips between the check and the insert. So the rules
 * live in [LedgerRepository.approve], where the transaction is, and this class
 * is the single audited door into it. `LedgerSingleWriterTest` fails the build
 * if anything else opens that door.
 */
public class ApproveTransactionUseCase @Inject constructor(
    private val ledger: LedgerRepository,
) {
    /** @return the committed entry, or the reason it was refused. */
    public suspend operator fun invoke(request: ApprovalRequest): LedgerResult<LedgerEntry> =
        ledger.approve(request)
}

/**
 * The 30-day orphan sweep, run once on app open (§6.1.2).
 *
 * @return how many rows went, so a diagnostics screen can say.
 */
public class PurgeAbandonedDraftsUseCase @Inject constructor(
    private val drafts: DraftRepository,
) {
    public suspend operator fun invoke(): Int = drafts.purgeAbandoned()
}
