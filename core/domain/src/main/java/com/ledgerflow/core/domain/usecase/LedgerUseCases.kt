package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSlot
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

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
 * Combinations already used in this book, for §5.4's repeat-expense chips.
 *
 * One ledger at a time. A combined list would be the netted view Law 2 forbids,
 * and would offer a credit category on a debit form.
 */
public class ObserveRecentCombosUseCase @Inject constructor(
    private val ledger: LedgerRepository,
) {
    public operator fun invoke(
        ledger: LedgerType,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<EntryCombo>> = this.ledger.observeRecentCombos(ledger, limit)

    private companion object {
        /** Enough to fill a chip row twice over without scrolling forever. */
        private const val DEFAULT_LIMIT = 8
    }
}

/** Loads the draft for a form that is opening (BUG6). */
public class FindDraftUseCase @Inject constructor(
    private val drafts: DraftRepository,
) {
    public suspend operator fun invoke(slot: DraftSlot): EntryDraft? = drafts.find(slot)
}

/**
 * Persists in-flight form state.
 *
 * Debouncing is the caller's job, not this class's: the 300 ms window belongs to
 * the form's keystroke stream, and a use case that owned a timer would be a use
 * case with a lifetime.
 */
public class SaveDraftUseCase @Inject constructor(
    private val drafts: DraftRepository,
) {
    public suspend operator fun invoke(
        slot: DraftSlot,
        payloadJson: String,
        payloadVersion: Int,
    ): EntryDraft = drafts.save(slot, payloadJson, payloadVersion)
}

/**
 * Removes a draft the user is finished with — saved, or explicitly abandoned.
 *
 * Never called speculatively. Every deletion here is an act of the user, which
 * is the entire distinction between this design and the singleton D-06 rejected.
 */
public class DiscardDraftUseCase @Inject constructor(
    private val drafts: DraftRepository,
) {
    public suspend operator fun invoke(slot: DraftSlot) {
        drafts.discard(slot)
    }
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
