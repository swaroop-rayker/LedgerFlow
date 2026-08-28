package com.ledgerflow.core.testing.inbox

import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingRepository
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.model.PendingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [PendingRepository].
 *
 * A fake rather than a mock, per CLAUDE.md §12: the interesting behaviours are
 * stateful — discard then restore, approve then approve again — and a mock would
 * only assert that a method was called.
 *
 * The state transitions are guarded the same way the real DAO's statements are,
 * because those guards are the behaviour under test. `discard` refuses an
 * already-approved row; `markApproved` refuses anything not `PENDING`. A fake
 * that let those through would make [PendingRepository]'s idempotency contract
 * look satisfied by code that does not satisfy it.
 */
public class FakePendingRepository(
    initial: List<PendingTransaction> = emptyList(),
) : PendingRepository {

    private val rows = MutableStateFlow(initial.associateBy { it.id })

    /** `ledger_entry` rows keyed by the candidate that produced them. */
    public val approvedEntries: MutableMap<String, String> = mutableMapOf()

    public fun put(row: PendingTransaction) {
        rows.value = rows.value + (row.id to row)
    }

    public fun get(id: String): PendingTransaction? = rows.value[id]

    /**
     * Every row the fake holds, keyed by id.
     *
     * For assertions about what a *destructive* call left behind. `observe`
     * cannot answer that: it filters by the caller's filter, so a row erased
     * from one and a row that was never on it look identical through it.
     */
    public fun snapshot(): Map<String, PendingTransaction> = rows.value

    override fun observe(filter: InboxFilter): Flow<List<PendingTransaction>> =
        rows.map { current ->
            current.values
                .filter { row ->
                    when (filter) {
                        InboxFilter.PENDING ->
                            row.status == PendingStatus.PENDING && !row.isSuppressed
                        InboxFilter.SUPPRESSED -> row.isSuppressed
                        InboxFilter.DISCARDED -> row.status == PendingStatus.DISCARDED
                        InboxFilter.FAILED -> row.status == PendingStatus.FAILED
                    }
                }
                .sortedByDescending { it.createdAt }
        }

    override fun observePendingCount(): Flow<Int> =
        observe(InboxFilter.PENDING).map { it.size }

    override suspend fun find(id: String): PendingTransaction? = rows.value[id]

    override suspend fun discard(id: String): Boolean =
        transition(id, from = PendingStatus.PENDING, to = PendingStatus.DISCARDED)

    override suspend fun restore(id: String): Boolean =
        transition(id, from = PendingStatus.DISCARDED, to = PendingStatus.PENDING)

    /**
     * Persists the review screen's typing (v8, BUG6).
     *
     * **Binds `PENDING` like the real statement does**, and that is the point of
     * modelling it here at all: a debounce tick can still be in flight when the
     * user taps Approve, and a fake that accepted the write would let a test
     * pass over a resolution that the database would have refused.
     */
    override suspend fun saveReviewDraft(id: String, json: String?): Boolean {
        val row = rows.value[id] ?: return false
        if (row.status != PendingStatus.PENDING) return false
        rows.value = rows.value + (id to row.copy(reviewDraftJson = json))
        return true
    }

    /**
     * Both guards modelled, not just the obvious one.
     *
     * A fake that only checked the filter would let a test pass over an
     * approved-and-suppressed row that the real statement refuses — and that row
     * is a `ledger_entry`'s only record of where it came from.
     */
    private fun erasable(row: PendingTransaction): Boolean =
        row.approvedEntryId == null &&
            (
                row.status == PendingStatus.DISCARDED ||
                    row.status == PendingStatus.FAILED ||
                    row.isSuppressed
                )

    override suspend fun purge(ids: List<String>): Int {
        val doomed = ids.mapNotNull { rows.value[it] }.filter(::erasable).map { it.id }.toSet()
        rows.value = rows.value - doomed
        return doomed.size
    }

    override suspend fun purgeAll(filter: InboxFilter): Int {
        val doomed = rows.value.values
            .filter { row ->
                erasable(row) && when (filter) {
                    InboxFilter.DISCARDED -> row.status == PendingStatus.DISCARDED
                    InboxFilter.FAILED -> row.status == PendingStatus.FAILED
                    InboxFilter.SUPPRESSED -> row.isSuppressed
                    InboxFilter.PENDING -> false
                }
            }
            .map { it.id }
            .toSet()
        rows.value = rows.value - doomed
        return doomed.size
    }

    override suspend fun markApproved(id: String, entryId: String): Boolean {
        val row = rows.value[id] ?: return false
        if (row.status != PendingStatus.PENDING) return false
        rows.value = rows.value + (
            id to row.copy(
                status = PendingStatus.APPROVED,
                reviewedAt = row.createdAt + 1,
                approvedEntryId = entryId,
                // The real statement clears it in the same UPDATE, so a
                // resolved candidate can never carry stale typing (v8).
                reviewDraftJson = null,
            )
            )
        return true
    }

    override suspend fun findApprovedEntryId(pendingId: String): String? =
        approvedEntries[pendingId]

    private fun transition(id: String, from: PendingStatus, to: PendingStatus): Boolean {
        val row = rows.value[id] ?: return false
        if (row.status != from) return false
        rows.value = rows.value + (
            id to row.copy(
                status = to,
                reviewedAt = if (to == PendingStatus.DISCARDED) row.createdAt + 1 else null,
                // Discard clears the draft in the same statement (v8). Restore
                // does not put it back -- the typing was thrown away with the
                // candidate, and inventing it on the way back would be worse
                // than an empty form.
                reviewDraftJson = null,
            )
            )
        return true
    }
}
