package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.inbox.InboxNotifier
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.recurring.RecurringReminders
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.recurring.RecurringSchedule
import javax.inject.Inject

/** What one pass did, for the worker's record. Counts only. */
public data class RecurringPassSummary(
    val reminded: Int = 0,
    val matched: Int = 0,
    val candidates: Int = 0,
    val skipped: Int = 0,
)

/**
 * The daily recurring pass (V2-PLAN §4.2; owner 2026-11-09).
 *
 * For each item that is not paused, its earliest unresolved due date:
 * - **matched** as soon as something captured in its window answers it
 *   (three days before to two after), and nothing more happens for it;
 * - **reminded** on the chosen day before it, once, while unmatched;
 * - once its window has closed with nothing captured, **an Inbox candidate**
 *   when the item adds to the Inbox, or **skipped** when it only reminds.
 *
 * Then the next due date, and so on, so a phone that was off for a while
 * catches up (at most [MAX_CATCH_UP] due dates an item per pass).
 *
 * **Never the ledger** (Law 1): the most this writes is a candidate, through
 * the one candidate writer, which the user approves like any other. A
 * candidate is announced like any capture; a reminder goes to its own channel.
 */
public class RunRecurringPassUseCase @Inject constructor(
    private val repository: RecurringRepository,
    private val reminders: RecurringReminders,
    private val inbox: InboxNotifier,
    private val clock: Clock,
) {

    public suspend operator fun invoke(): RecurringPassSummary {
        val today = LocalDates.of(clock.nowMillis())
        return repository.activeItems().fold(RecurringPassSummary()) { summary, item -> summary + pass(item, today) }
    }

    private suspend fun pass(item: RecurringItem, today: Int): RecurringPassSummary {
        var summary = RecurringPassSummary()
        var due = item.nextDue
        var steps = 0
        while (steps++ < MAX_CATCH_UP && today >= due - RecurringSchedule.MATCH_DAYS_BEFORE) {
            val resolved = resolve(item, due, today)
            if (resolved == null) {
                // Still open: the most it may do today is remind.
                summary += remindIfDue(item, due, today)
                break
            }
            summary += resolved
            due = RecurringSchedule.following(item.cadence, item.everyDays, item.anchorDate, due)
            repository.advance(item.id, due)
        }
        return summary
    }

    /**
     * A due date's verdict, or null while it is still open. A candidate the
     * writer could not write leaves it open, to be tried on the next pass.
     */
    private suspend fun resolve(item: RecurringItem, due: Int, today: Int): RecurringPassSummary? {
        val earlier = repository.occurrence(item.id, due)
        val match = repository.findMatch(item, due)
        return when {
            match != null -> {
                record(item, due, OccurrenceOutcome.MATCHED, earlier, match)
                RecurringPassSummary(matched = 1)
            }
            today <= RecurringSchedule.windowClosesAfter(due) -> null
            !item.toInbox -> {
                record(item, due, OccurrenceOutcome.SKIPPED, earlier, match = null)
                RecurringPassSummary(skipped = 1)
            }
            else -> addToInbox(item, due, earlier)
        }
    }

    /** The candidate, announced like any capture; null leaves the due date open for the next pass. */
    private suspend fun addToInbox(
        item: RecurringItem,
        due: Int,
        earlier: RecurringOccurrence?,
    ): RecurringPassSummary? {
        val pendingId = repository.writeCandidate(item, due) ?: return null
        record(item, due, OccurrenceOutcome.CANDIDATE, earlier, RecurringMatch.Candidate(pendingId))
        inbox.notifyCandidate(pendingId)
        return RecurringPassSummary(candidates = 1)
    }

    private suspend fun remindIfDue(item: RecurringItem, due: Int, today: Int): RecurringPassSummary {
        val earlier = repository.occurrence(item.id, due)
        val inReminderDays = today in due - item.remindDaysBefore..due
        if (!inReminderDays || earlier?.remindedAt != null) return RecurringPassSummary()
        reminders.remind(item, due)
        repository.record(
            RecurringOccurrence(item.id, due, OccurrenceOutcome.WAITING, null, null, remindedAt = clock.nowMillis()),
        )
        return RecurringPassSummary(reminded = 1)
    }

    private suspend fun record(
        item: RecurringItem,
        due: Int,
        outcome: OccurrenceOutcome,
        earlier: RecurringOccurrence?,
        match: RecurringMatch?,
    ) {
        repository.record(
            RecurringOccurrence(
                itemId = item.id,
                dueDate = due,
                outcome = outcome,
                pendingId = (match as? RecurringMatch.Candidate)?.id,
                entryId = (match as? RecurringMatch.Entry)?.id,
                remindedAt = earlier?.remindedAt,
            ),
        )
    }

    private operator fun RecurringPassSummary.plus(other: RecurringPassSummary) = RecurringPassSummary(
        reminded = reminded + other.reminded,
        matched = matched + other.matched,
        candidates = candidates + other.candidates,
        skipped = skipped + other.skipped,
    )

    private companion object {
        /** Due dates one item may resolve in a pass: a year of a monthly item. */
        const val MAX_CATCH_UP = 12
    }
}
