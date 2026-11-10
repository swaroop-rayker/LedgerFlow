package com.ledgerflow.core.testing.recurring

import com.ledgerflow.core.domain.recurring.RecurringDetails
import com.ledgerflow.core.domain.recurring.RecurringError
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.recurring.RecurringReminders
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.recurring.RecurringResult
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Recurring items in memory (V2-7). What answers a due date is whatever a
 * test puts in [matches]; the SQL that finds it is tested on the device.
 */
public class FakeRecurringRepository : RecurringRepository {

    public val items: MutableStateFlow<List<RecurringItem>> = MutableStateFlow(emptyList())
    public val occurrences: MutableMap<Pair<String, Int>, RecurringOccurrence> = mutableMapOf()
    public val dismissed: MutableStateFlow<Set<Pair<String, LedgerType>>> = MutableStateFlow(emptySet())

    /** What answers (item id, due date). */
    public val matches: MutableMap<Pair<String, Int>, RecurringMatch> = mutableMapOf()

    /** Candidates written, as (item id, due date). */
    public val candidates: MutableList<Pair<String, Int>> = mutableListOf()

    /** False makes [writeCandidate] fail, as a refused vault would. */
    public var candidatesWork: Boolean = true

    override fun observeItems(): Flow<List<RecurringItem>> = items

    override fun observeOccurrences(itemId: String, limit: Int): Flow<List<RecurringOccurrence>> =
        items.map { occurrences.values.filter { it.itemId == itemId }.sortedByDescending { it.dueDate }.take(limit) }

    override fun observeDismissed(): Flow<Set<Pair<String, LedgerType>>> = dismissed

    override suspend fun create(details: RecurringDetails): RecurringResult<RecurringItem> {
        val item = RecurringItem(
            id = "item-${items.value.size}", name = details.name, ledger = details.ledger, amount = details.amount,
            amountVaries = details.amountVaries, merchantId = details.merchantId, categoryId = details.categoryId,
            subcategoryId = details.subcategoryId, cadence = details.cadence, everyDays = details.everyDays,
            anchorDate = details.firstDue, nextDue = details.firstDue, remindDaysBefore = details.remindDaysBefore,
            toInbox = details.toInbox, paused = false,
        )
        items.value = items.value + item
        return RecurringResult.Success(item)
    }

    override suspend fun update(id: String, details: RecurringDetails): RecurringResult<Unit> =
        RecurringResult.Failure(RecurringError.NotFound(id))

    override suspend fun delete(id: String): RecurringResult<Unit> {
        items.value = items.value.filterNot { it.id == id }
        return RecurringResult.Success(Unit)
    }

    override suspend fun setPaused(id: String, paused: Boolean): RecurringResult<Unit> {
        items.value = items.value.map { if (it.id == id) it.copy(paused = paused) else it }
        return RecurringResult.Success(Unit)
    }

    override suspend fun dismiss(merchantId: String, ledger: LedgerType) {
        dismissed.value = dismissed.value + (merchantId to ledger)
    }

    override suspend fun activeItems(): List<RecurringItem> = items.value.filterNot { it.paused }

    override suspend fun occurrence(itemId: String, dueDate: Int): RecurringOccurrence? = occurrences[itemId to dueDate]

    override suspend fun findMatch(item: RecurringItem, dueDate: Int): RecurringMatch? = matches[item.id to dueDate]

    override suspend fun writeCandidate(item: RecurringItem, dueDate: Int): String? {
        if (!candidatesWork) return null
        candidates += item.id to dueDate
        return "recurring:${item.id}:$dueDate"
    }

    override suspend fun record(occurrence: RecurringOccurrence) {
        occurrences[occurrence.itemId to occurrence.dueDate] = occurrence
    }

    override suspend fun advance(itemId: String, nextDue: Int) {
        items.value = items.value.map { if (it.id == itemId) it.copy(nextDue = nextDue) else it }
    }
}

/** Every reminder posted, as (item id, due date). */
public class RecordingRecurringReminders : RecurringReminders {
    public val posted: MutableList<Pair<String, Int>> = mutableListOf()

    override suspend fun remind(item: RecurringItem, dueDate: Int) {
        posted += item.id to dueDate
    }
}
