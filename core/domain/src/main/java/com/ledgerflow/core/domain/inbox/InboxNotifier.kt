package com.ledgerflow.core.domain.inbox

/**
 * Tells the user a candidate is waiting (SPEC.md §5.1). P2-7.
 *
 * The last step of §5.1's pipeline diagram, and the only one that leaves the
 * app. Everything above it has already happened: the message is captured, the
 * rule engine has had its go, dedupe has decided, and a `pending_transaction`
 * row exists. This announces that row and nothing else.
 *
 * **Nothing here may reach `ledger_entry`.** Law 1's single writer is
 * `ApproveTransactionUseCase`, and the notification's `[Approve]` action goes
 * through [ApprovePendingUseCase] exactly as the review screen does. A
 * notification is a surface, not a second door.
 *
 * **A suppressed candidate is never announced** (§3.1). It is retained and
 * visible under the Inbox's "Suppressed" filter, which is a different promise
 * from being buzzed about: a duplicate the user never needed to see must not
 * wake their phone. `ParseCapturedMessages` calls [notifyCandidate] on a
 * `Created` outcome only, and `SuppressedCandidateDoesNotNotifyTest` is what
 * keeps that true.
 *
 * A port in `:core:domain` rather than a class beside the pipeline, for the
 * reason [com.ledgerflow.core.domain.ingest.IngestWorkTrigger] is one: the
 * caller is unit-tested on the JVM, and no JVM unit test has a
 * `NotificationManager`. The implementation is Android's and lives in
 * `:feature:ingest` beside the pipeline that calls it.
 *
 * **Asking is always safe.** Both calls are idempotent and neither throws: a
 * notification the user has already dismissed re-posts, and cancelling one that
 * was never posted does nothing. Posting can fail entirely — the runtime
 * `POST_NOTIFICATIONS` grant is the user's to withhold — and that is not an
 * error the pipeline should care about, because the candidate is on disk either
 * way and the Inbox is the surface that never depends on a grant.
 */
public interface InboxNotifier {

    /**
     * Announce one candidate.
     *
     * Takes an id rather than a [PendingTransaction] so the pipeline hands over
     * what it has — the write's outcome — and the implementation reads what it
     * needs to render. That also means the notification shows the row as it
     * actually landed, rather than as the caller believed it would.
     */
    public suspend fun notifyCandidate(pendingId: String)

    /**
     * Take one back.
     *
     * Two callers, and the second is the interesting one. The obvious case is a
     * candidate that has been reviewed — approved or discarded — and should stop
     * sitting in the shade. The other is §3.1's flip: a later, higher-confidence
     * arrival supersedes a candidate that was already announced, and the
     * incumbent becomes a *suppressed* row. It was legitimately notified when it
     * was the winner; once it is not, leaving it in the shade would send the user
     * to review a duplicate.
     */
    public suspend fun cancelCandidate(pendingId: String)
}
