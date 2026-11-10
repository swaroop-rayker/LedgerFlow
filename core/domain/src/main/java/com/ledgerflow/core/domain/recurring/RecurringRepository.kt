package com.ledgerflow.core.domain.recurring

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/** How often an item repeats. Monthly and longer keep the anchor's day of month. */
public enum class Cadence { WEEKLY, MONTHLY, QUARTERLY, YEARLY, EVERY_N_DAYS }

/** What one due date turned into. */
public enum class OccurrenceOutcome {
    /** Not yet resolved: before or inside its match window. */
    WAITING,

    /** Something captured answered it: an entry, or a candidate in the Inbox. */
    MATCHED,

    /** Nothing was captured, so a candidate was written to the Inbox. */
    CANDIDATE,

    /** Nothing was captured, and the item only reminds. */
    SKIPPED,
}

/**
 * A recurring payment or receipt (V2-PLAN §4.2, schema v16). One book;
 * [amount] is what it is expected to be, base currency. [nextDue] and the
 * other days are days since epoch.
 */
public data class RecurringItem(
    val id: String,
    val name: String,
    val ledger: LedgerType,
    val amount: Money,
    val amountVaries: Boolean,
    val merchantId: String?,
    val categoryId: String?,
    val subcategoryId: String?,
    val cadence: Cadence,
    val everyDays: Int?,
    val anchorDate: Int,
    val nextDue: Int,
    val remindDaysBefore: Int,
    val toInbox: Boolean,
    val paused: Boolean,
)

/** What one due date turned into, and what answered it. */
public data class RecurringOccurrence(
    val itemId: String,
    val dueDate: Int,
    val outcome: OccurrenceOutcome,
    val pendingId: String?,
    val entryId: String?,
    val remindedAt: Long?,
)

/** What the form saves. [firstDue] becomes the anchor and the next due date. */
public data class RecurringDetails(
    val name: String,
    val ledger: LedgerType,
    val amount: Money,
    val amountVaries: Boolean,
    val merchantId: String?,
    val categoryId: String?,
    val subcategoryId: String?,
    val cadence: Cadence,
    val everyDays: Int?,
    val firstDue: Int,
    val remindDaysBefore: Int,
    val toInbox: Boolean,
)

/** What answered a due date. */
public sealed interface RecurringMatch {
    /** An entry already in its book. */
    public data class Entry(val id: String) : RecurringMatch

    /** A captured candidate still waiting in the Inbox. */
    public data class Candidate(val id: String) : RecurringMatch
}

public sealed interface RecurringError {
    public data object NameBlank : RecurringError
    public data object AmountNotPositive : RecurringError

    /** `EVERY_N_DAYS` needs a number of days, at least one. */
    public data object EveryDaysMissing : RecurringError

    /** "Remind me" is the day of, the day before, or three days before. */
    public data object ReminderNotOffered : RecurringError
    public data class NotFound(val id: String) : RecurringError
}

public sealed interface RecurringResult<out T> {
    public data class Success<T>(val value: T) : RecurringResult<T>
    public data class Failure(val error: RecurringError) : RecurringResult<Nothing>
}

/**
 * Recurring items (V2-PLAN §4.2).
 *
 * The second half of this port is the daily pass's, which runs with no
 * Activity alive: every method it calls opens the vault itself (`CLAUDE.md`
 * §7), and none of them writes `ledger_entry` (Law 1).
 */
public interface RecurringRepository {

    public fun observeItems(): Flow<List<RecurringItem>>

    /** One item's log, newest first. */
    public fun observeOccurrences(itemId: String, limit: Int): Flow<List<RecurringOccurrence>>

    /** Merchants, per book, whose suggestion was dismissed. */
    public fun observeDismissed(): Flow<Set<Pair<String, LedgerType>>>

    public suspend fun create(details: RecurringDetails): RecurringResult<RecurringItem>

    /** Changing the schedule restarts it at [RecurringDetails.firstDue]. */
    public suspend fun update(id: String, details: RecurringDetails): RecurringResult<Unit>

    /** For good, with its log (owner, 2026-11-09). Entries are untouched. */
    public suspend fun delete(id: String): RecurringResult<Unit>

    public suspend fun setPaused(id: String, paused: Boolean): RecurringResult<Unit>

    /** "Not recurring" on a suggestion. */
    public suspend fun dismiss(merchantId: String, ledger: LedgerType)

    // The daily pass.

    /** Every item not paused; empty when the vault cannot be opened. */
    public suspend fun activeItems(): List<RecurringItem>

    public suspend fun occurrence(itemId: String, dueDate: Int): RecurringOccurrence?

    /**
     * Something captured in [item]'s book from three days before [dueDate] to
     * two after that answers it, and no other due date has claimed: the same
     * amount (any amount when it varies) and, when both are known, the same
     * merchant. An item whose amount varies needs its merchant to match.
     */
    public suspend fun findMatch(item: RecurringItem, dueDate: Int): RecurringMatch?

    /**
     * An Inbox candidate for [dueDate], reference `recurring:<item>:<due>`,
     * through the one candidate writer. The id when one was written or was
     * already there; null when the write failed.
     */
    public suspend fun writeCandidate(item: RecurringItem, dueDate: Int): String?

    public suspend fun record(occurrence: RecurringOccurrence)

    /** Moves the item's next due date on, after one is resolved. */
    public suspend fun advance(itemId: String, nextDue: Int)
}

/** Every recurring item, for a reader that needs the list once (Home's Coming up). Empty while locked. */
public fun interface RecurringItemsReader {
    public suspend fun items(): List<RecurringItem>
}

/** Asks for a recurring pass soon, after an item is saved, so a reminder already due is not missed. */
public fun interface RecurringPassTrigger {
    public fun runSoon()
}

/** Posts the "Rent due tomorrow" reminder. Implemented beside its channel, in `:feature:tools`. */
public fun interface RecurringReminders {
    public suspend fun remind(item: RecurringItem, dueDate: Int)
}

/**
 * Whether reminders can reach the user, for Settings > Notifications. Like
 * budget alerts, Android's channel is the only switch (V2-PLAN §3.4).
 */
public fun interface ReminderChannel {
    public fun state(): com.ledgerflow.core.domain.analytics.AlertChannelState
}
