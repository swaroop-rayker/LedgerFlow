package com.ledgerflow.core.testing.inbox

import com.ledgerflow.core.domain.inbox.InboxNotifier

/**
 * An [InboxNotifier] that records instead of posting.
 *
 * A fake rather than a mock, per the same reasoning as its neighbours: what the
 * tests here assert is a *sequence* — this candidate was announced, that one
 * never was, and the superseded one was taken back — and the order between them
 * is the behaviour §3.1 cares about. A mock would verify that a method was
 * called and lose the ordering that makes the assertion mean anything.
 *
 * The two lists are kept separate rather than as one event log because the
 * question tests actually ask is "was this id ever announced?", and the useful
 * failure message is the list of ids that were. [events] is there for the one
 * assertion that needs the interleaving.
 */
public class RecordingInboxNotifier : InboxNotifier {

    /** Every id passed to [notifyCandidate], in order. */
    public val notified: MutableList<String> = mutableListOf()

    /** Every id passed to [cancelCandidate], in order. */
    public val cancelled: MutableList<String> = mutableListOf()

    /** Both, interleaved — for the tests that care which came first. */
    public val events: MutableList<Event> = mutableListOf()

    override suspend fun notifyCandidate(pendingId: String) {
        notified += pendingId
        events += Event.Notified(pendingId)
    }

    override suspend fun cancelCandidate(pendingId: String) {
        cancelled += pendingId
        events += Event.Cancelled(pendingId)
    }

    public fun clear() {
        notified.clear()
        cancelled.clear()
        events.clear()
    }

    /** One call, as it happened. */
    public sealed interface Event {
        public val pendingId: String

        public data class Notified(override val pendingId: String) : Event

        public data class Cancelled(override val pendingId: String) : Event
    }
}
