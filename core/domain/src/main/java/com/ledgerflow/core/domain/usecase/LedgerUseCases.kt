package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.vault.StorageMaintenance
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
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
 * **The only thing in LedgerFlow that may remove a `ledger_entry` row.**
 *
 * The sibling of [ApproveTransactionUseCase], and it exists for the same
 * reason. Law 1 names inserts specifically, so a delete is not literally
 * covered -- but the law's purpose is that every write to the ledger goes
 * through one door that can be audited, and removing an entry is a write with
 * consequences an insert does not have: it changes past totals. Leaving it as a
 * repository call any feature could make would mean the one operation that can
 * quietly rewrite history is also the one nothing watches.
 *
 * Thin on purpose, exactly like the approval: the rules that matter -- the
 * ledger predicate, the `deleted_at IS NULL` guard -- live in the statement,
 * where they cannot be bypassed. This is the audited door, not a second place
 * to put logic. `LedgerSingleWriterTest` fails the build if anything else opens
 * it.
 */
public class DeleteEntryUseCase @Inject constructor(
    private val ledger: LedgerRepository,
) {
    /** @return [Unit] on success, or why there was nothing to remove. */
    public suspend operator fun invoke(
        book: LedgerType,
        id: String,
    ): LedgerResult<Unit> = ledger.softDeleteEntry(book, id)
}

/**
 * **The only thing in LedgerFlow that destroys committed ledger data.**
 *
 * [DeleteEntryUseCase] hides an entry and keeps every byte of it;
 * this erases what has already been hidden. It is the third audited door into
 * `ledger_entry`, and the one that most needs to be a door at all -- the other
 * two are recoverable and this is not.
 *
 * Both books, in one call, because "erase what I deleted" is not a per-ledger
 * question the way a list or a total is. Law 2 is still honoured underneath:
 * the repository takes a [LedgerType] and this issues one statement per book,
 * so no single statement ever spans them.
 *
 * The compaction runs **after** the deletes and only if something was actually
 * removed. Skipping it on an empty purge is not just an optimisation: rewriting
 * the whole database to reclaim nothing would be the most expensive no-op in
 * the app. It arrives through [StorageMaintenance] rather than through
 * [LedgerRepository], because a taxonomy purge now has the same obligation and
 * neither should reach through the other's port to discharge it (ADR-0016).
 *
 * @return how many entries were destroyed.
 */
public class PurgeDeletedEntriesUseCase @Inject constructor(
    private val ledger: LedgerRepository,
    private val storage: StorageMaintenance,
) {
    /** Empties the bin: every binned entry in both books. */
    public suspend operator fun invoke(): Int {
        val purged = LedgerType.entries.sumOf { book -> ledger.purgeDeletedEntries(book) }
        if (purged > 0) storage.compactStorage()
        return purged
    }

    /**
     * Destroys only the entries the user picked.
     *
     * Each carries its own book, because the bin lists both and an id alone
     * cannot say which statement should see it (Law 2).
     *
     * Compaction runs once at the end rather than per entry: `VACUUM` rewrites
     * the whole database, so doing it inside the loop would rewrite the file
     * once per selected row.
     */
    public suspend operator fun invoke(entries: List<BinnedRef>): Int {
        val purged = entries.sumOf { ledger.purgeDeletedEntry(it.ledger, it.id) }
        if (purged > 0) storage.compactStorage()
        return purged
    }
}

/**
 * Puts binned entries back into their books.
 *
 * The fourth audited door into `ledger_entry`, and the only one that *adds*
 * rather than removes — which is precisely why it needs the same guard as the
 * others. It is a write, and a write that makes past totals change again.
 *
 * Nothing is compacted here: restoring frees no pages, and `VACUUM` on a
 * database that has not shrunk is the most expensive no-op in the app.
 *
 * @return how many were restored. Fewer than asked means some were already
 *   gone, which is the honest outcome of a stale list rather than an error.
 */
public class RestoreEntryUseCase @Inject constructor(
    private val ledger: LedgerRepository,
) {
    public suspend operator fun invoke(entries: List<BinnedRef>): Int =
        entries.count { ledger.restoreEntry(it.ledger, it.id) is LedgerResult.Success }
}

/**
 * One binned entry, named by the pair any write about it needs.
 *
 * The book travels with the id everywhere, because an id is a UUIDv7 with no
 * ledger encoded in it. On every other surface the screen already knows which
 * book it is showing; the bin shows both, so the pairing has to be explicit or
 * a statement could be aimed at the wrong one (Law 2).
 */
public data class BinnedRef(val ledger: LedgerType, val id: String)

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
