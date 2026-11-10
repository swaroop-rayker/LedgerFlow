package com.ledgerflow.core.data.recurring

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.PendingCandidateWriter
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.RecurringDismissalEntity
import com.ledgerflow.core.database.entity.RecurringItemEntity
import com.ledgerflow.core.database.entity.RecurringOccurrenceEntity
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringDetails
import com.ledgerflow.core.domain.recurring.RecurringError
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.recurring.RecurringResult
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Recurring items over Room (V2-PLAN §4.2, schema v16).
 *
 * **The daily pass's methods open the vault themselves** (`CLAUDE.md` §7):
 * the worker runs with no Activity alive, where `requireDatabase()` throws
 * into a `runCatching` and comes back as a quiet nothing. They return the
 * empty answer when the vault cannot be opened, and the pass tries again
 * tomorrow.
 *
 * **Nothing here writes `ledger_entry`** (Law 1). The most it writes is an
 * Inbox candidate, through [PendingCandidateWriter], the one door every source
 * shares, with the decision about duplicates made here
 * ([RecurringMatcher]) as a statement import makes its own.
 */
@Singleton
public class DefaultRecurringRepository @Inject constructor(
    private val session: VaultSession,
    private val writer: PendingCandidateWriter,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RecurringRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeItems(): Flow<List<RecurringItem>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.recurringDao()?.observeItems()?.map { rows -> rows.mapNotNull { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOccurrences(itemId: String, limit: Int): Flow<List<RecurringOccurrence>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.recurringDao()?.observeOccurrences(itemId, limit)
                ?.map { rows -> rows.mapNotNull { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDismissed(): Flow<Set<Pair<String, LedgerType>>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.recurringDao()?.observeDismissals()
                ?.map { rows -> rows.map { it.merchantId to it.ledger }.toSet() }
                ?: flowOf(emptySet())
        }

    override suspend fun create(details: RecurringDetails): RecurringResult<RecurringItem> = withContext(io) {
        refusal(details)?.let { return@withContext RecurringResult.Failure(it) }
        val now = clock.nowMillis()
        val entity = details.toEntity(id = ids.generate(), createdAt = now, updatedAt = now)
        session.requireDatabase().recurringDao().insertItem(entity)
        RecurringResult.Success(requireNotNull(entity.toDomain()) { "a cadence this build wrote" })
    }

    override suspend fun update(id: String, details: RecurringDetails): RecurringResult<Unit> = withContext(io) {
        refusal(details)?.let { return@withContext RecurringResult.Failure(it) }
        val dao = session.requireDatabase().recurringDao()
        val current = dao.item(id) ?: return@withContext RecurringResult.Failure(RecurringError.NotFound(id))
        dao.updateItem(
            details.toEntity(id = id, createdAt = current.createdAt, updatedAt = clock.nowMillis())
                .copy(paused = current.paused),
        )
        RecurringResult.Success(Unit)
    }

    override suspend fun delete(id: String): RecurringResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().recurringDao().deleteItem(id)
        if (affected == 0) RecurringResult.Failure(RecurringError.NotFound(id)) else RecurringResult.Success(Unit)
    }

    override suspend fun setPaused(id: String, paused: Boolean): RecurringResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().recurringDao()
        val current = dao.item(id) ?: return@withContext RecurringResult.Failure(RecurringError.NotFound(id))
        dao.updateItem(current.copy(paused = paused, updatedAt = clock.nowMillis()))
        RecurringResult.Success(Unit)
    }

    override suspend fun dismiss(merchantId: String, ledger: LedgerType) {
        withContext(io) {
            session.requireDatabase().recurringDao()
                .upsertDismissal(RecurringDismissalEntity(merchantId, ledger, clock.nowMillis()))
        }
    }

    override suspend fun activeItems(): List<RecurringItem> = withContext(io) {
        session.openForBackgroundWork()?.recurringDao()?.activeItems()?.mapNotNull { it.toDomain() }.orEmpty()
    }

    override suspend fun occurrence(itemId: String, dueDate: Int): RecurringOccurrence? = withContext(io) {
        session.openForBackgroundWork()?.recurringDao()?.occurrence(itemId, dueDate)?.toDomain()
    }

    override suspend fun findMatch(item: RecurringItem, dueDate: Int): RecurringMatch? = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext null
        RecurringMatcher(database).match(item, dueDate)
    }

    override suspend fun writeCandidate(item: RecurringItem, dueDate: Int): String? = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext null
        val refId = referenceOf(item.id, dueDate)
        val merchantName = item.merchantId?.let { database.merchantDao().byId(it)?.canonicalName }
        val extracted = ExtractedTransaction(
            amount = item.amount,
            currency = database.appMetaDao().value(AppMetaEntity.KEY_BASE_CURRENCY) ?: DEFAULT_CURRENCY,
            direction = if (item.ledger == LedgerType.DEBIT) ExtractedDirection.DEBIT else ExtractedDirection.CREDIT,
            merchantRaw = merchantName ?: item.name,
            // Noon on the due date: the day the user said the payment falls on.
            occurredAt = LocalDate.ofEpochDay(dueDate.toLong()).atTime(NOON, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            // Stated by the user, not guessed out of prose.
            confidence = 1.0,
        )
        val candidate = PendingCandidate(EntrySource.RECURRING, extracted, DedupeKey.compute(extracted, refId))
        // The matcher already decided nothing captured answers it.
        val nothing = PendingCandidateWriter.Duplicates.Decided(null)
        when (val outcome = writer.write(database, refId, candidate, verdict = null, duplicates = nothing)) {
            is PendingWriteOutcome.Created -> outcome.pendingId
            is PendingWriteOutcome.AlreadyPending -> outcome.pendingId
            is PendingWriteOutcome.Suppressed -> outcome.pendingId
            is PendingWriteOutcome.Failed -> null
        }
    }

    override suspend fun record(occurrence: RecurringOccurrence) {
        withContext(io) {
            val database = session.openForBackgroundWork() ?: return@withContext
            database.withTransaction {
                val dao = database.recurringDao()
                val now = clock.nowMillis()
                val existing = dao.occurrence(occurrence.itemId, occurrence.dueDate)
                dao.upsertOccurrence(
                    RecurringOccurrenceEntity(
                        id = existing?.id ?: ids.generate(),
                        itemId = occurrence.itemId,
                        dueDate = occurrence.dueDate,
                        outcome = occurrence.outcome.name,
                        pendingId = occurrence.pendingId,
                        entryId = occurrence.entryId,
                        remindedAt = occurrence.remindedAt,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override suspend fun advance(itemId: String, nextDue: Int) {
        withContext(io) {
            val dao = session.openForBackgroundWork()?.recurringDao() ?: return@withContext
            val current = dao.item(itemId) ?: return@withContext
            dao.updateItem(current.copy(nextDue = nextDue, updatedAt = clock.nowMillis()))
        }
    }

    private fun refusal(details: RecurringDetails): RecurringError? = when {
        details.name.isBlank() -> RecurringError.NameBlank
        !details.amount.isPositive -> RecurringError.AmountNotPositive
        details.cadence == Cadence.EVERY_N_DAYS && (details.everyDays ?: 0) < 1 -> RecurringError.EveryDaysMissing
        details.remindDaysBefore !in REMINDER_CHOICES -> RecurringError.ReminderNotOffered
        else -> null
    }

    public companion object {
        /** A recurring candidate's reference: one per item and due date, so a second write is refused. */
        public fun referenceOf(itemId: String, dueDate: Int): String = "$REF_PREFIX$itemId:$dueDate"

        public const val REF_PREFIX: String = "recurring:"
        private const val DEFAULT_CURRENCY = "INR"
        private const val NOON = 12
        private val REMINDER_CHOICES = setOf(0, 1, 3)
    }
}

private fun RecurringDetails.toEntity(id: String, createdAt: Long, updatedAt: Long) = RecurringItemEntity(
    id = id,
    name = name.trim(),
    ledger = ledger,
    amountMinor = amount,
    amountVaries = amountVaries,
    merchantId = merchantId,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    cadence = cadence.name,
    everyDays = everyDays.takeIf { cadence == Cadence.EVERY_N_DAYS },
    anchorDate = firstDue,
    nextDue = firstDue,
    remindDaysBefore = remindDaysBefore,
    toInbox = toInbox,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Null for a cadence this build does not know: the item is left alone rather than guessed at. */
internal fun RecurringItemEntity.toDomain(): RecurringItem? {
    val cadence = Cadence.entries.firstOrNull { it.name == cadence } ?: return null
    return RecurringItem(
        id = id, name = name, ledger = ledger, amount = amountMinor, amountVaries = amountVaries,
        merchantId = merchantId, categoryId = categoryId, subcategoryId = subcategoryId, cadence = cadence,
        everyDays = everyDays, anchorDate = anchorDate, nextDue = nextDue, remindDaysBefore = remindDaysBefore,
        toInbox = toInbox, paused = paused,
    )
}

private fun RecurringOccurrenceEntity.toDomain(): RecurringOccurrence? {
    val outcome = OccurrenceOutcome.entries.firstOrNull { it.name == outcome } ?: return null
    return RecurringOccurrence(itemId, dueDate, outcome, pendingId, entryId, remindedAt)
}
