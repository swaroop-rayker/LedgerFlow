package com.ledgerflow.core.data.statement

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.ingest.PendingCandidateWriter
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.statement.CheckedRow
import com.ledgerflow.core.domain.statement.StatementCheck
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementImportRepository
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementReading
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.model.EntrySource
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Import a statement into the Inbox (V2-4, decision D8).
 *
 * **Every row is a candidate** written by [PendingCandidateWriter], the one
 * insert every source shares, so Law 1 is untouched: nothing here writes to
 * `ledger_entry`, and `LedgerSingleWriterTest` still has one door to guard.
 *
 * **"Already in LedgerFlow"** is decided here rather than by the writer's
 * capture window, which cannot see a payment made weeks before it was
 * imported: the same book, the same amount in the base currency, a date
 * within a day — in the ledger (through its view, Law 2) or among live
 * candidates. Each existing transaction answers for one row only, so a
 * statement's two ₹50 top-ups on one day are not both taken for the one the
 * SMS already caught. Such rows are still written, suppressed: kept and
 * visible under the Inbox's Suppressed filter, never silently dropped.
 *
 * **A row's reference is `import:<file fingerprint>:<line>`**, so importing the
 * same file again adds nothing, and a later "approve the whole import" can
 * find a batch without a schema change.
 */
@Singleton
public class DefaultStatementImportRepository @Inject constructor(
    private val session: VaultSession,
    private val documents: StatementDocumentSource,
    private val writer: PendingCandidateWriter,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : StatementImportRepository {

    override suspend fun open(documentUri: String): StatementOpened = withContext(io) {
        val base = baseCurrency(session.requireDatabase())
        documents.read(documentUri, StatementFiles.MAX_BYTES)?.let { StatementFiles.open(it, base) }
            ?: StatementOpened.Unreadable
    }

    override suspend fun check(table: StatementTable, columns: StatementColumns): StatementCheck = withContext(io) {
        val database = session.requireDatabase()
        val base = baseCurrency(database)
        val lines = StatementReading.read(table, columns, base)
        val reads = lines.filterIsInstance<StatementLine.Read>()
        val matches = Matcher(database, table, base).matchAll(reads)
        StatementCheck(
            baseCurrency = base,
            rows = reads.map { CheckedRow(it, alreadyThere = matches[it.line] != null) },
            unreadable = lines.filterIsInstance<StatementLine.Unreadable>(),
        )
    }

    override suspend fun addToInbox(table: StatementTable, columns: StatementColumns): StatementImported =
        withContext(io) {
            val database = session.requireDatabase()
            val base = baseCurrency(database)
            val reads = StatementReading.read(table, columns, base).filterIsInstance<StatementLine.Read>()
            val matches = Matcher(database, table, base).matchAll(reads)
            val outcomes = reads.map { line ->
                val refId = referenceOf(table, line)
                val extracted = ExtractedTransaction(
                    amount = line.amount,
                    currency = line.currency,
                    direction = line.direction,
                    merchantRaw = line.description,
                    occurredAt = line.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    // Read from a table cell by cell, not guessed out of prose.
                    confidence = 1.0,
                )
                val candidate = PendingCandidate(EntrySource.IMPORT, extracted, DedupeKey.compute(extracted, refId))
                writer.write(
                    database,
                    refId,
                    candidate,
                    verdict = null,
                    duplicates = PendingCandidateWriter.Duplicates.Decided(matches[line.line]),
                )
            }
            database.appMetaDao().put(
                AppMetaEntity(BATCH_PREFIX + table.fingerprint, "${clock.nowMillis()}|${reads.size}|${table.name}"),
            )
            StatementImported(
                added = outcomes.count { it is PendingWriteOutcome.Created },
                suppressed = outcomes.count { it is PendingWriteOutcome.Suppressed },
                alreadyImported = outcomes.count { it is PendingWriteOutcome.AlreadyPending },
                failed = outcomes.count { it is PendingWriteOutcome.Failed },
            )
        }

    private suspend fun baseCurrency(database: LedgerFlowDatabase): String =
        database.appMetaDao().value(AppMetaEntity.KEY_BASE_CURRENCY) ?: DEFAULT_CURRENCY

    /**
     * Finds what each row repeats, in file order, using each existing
     * transaction once. The answer is an id: this file's own earlier candidate
     * for the row, the candidate an entry was approved from, the entry itself
     * when it was typed in by hand, or a live candidate.
     */
    private class Matcher(
        private val database: LedgerFlowDatabase,
        private val table: StatementTable,
        private val base: String,
    ) {
        private val used = mutableSetOf<String>()
        private val ownPrefix = "$REF_PREFIX${table.fingerprint}:"

        suspend fun matchAll(reads: List<StatementLine.Read>): Map<Int, String?> =
            reads.associate { it.line to match(it) }

        private suspend fun match(line: StatementLine.Read): String? {
            database.pendingTransactionDao().idForRawRef(referenceOf(table, line))?.let { return it }
            if (line.currency != base) return null
            return inLedger(line) ?: inInbox(line)
        }

        private suspend fun inLedger(line: StatementLine.Read): String? {
            val day = line.date.toEpochDay().toInt()
            val dao = database.ledgerEntryDao()
            val ids = when (line.direction) {
                ExtractedDirection.DEBIT -> dao.debitIdsMatching(line.amount.minor, day - 1, day + 1)
                ExtractedDirection.CREDIT -> dao.creditIdsMatching(line.amount.minor, day - 1, day + 1)
                ExtractedDirection.UNKNOWN -> emptyList()
            }
            for (entryId in ids) {
                // The entry, or the candidate it was approved from: either one
                // already answering for an earlier row puts this entry out of play.
                val answer = if (entryId in used) {
                    null
                } else {
                    database.pendingTransactionDao().idApprovedAs(entryId) ?: entryId
                }
                if (answer != null && answer !in used) {
                    used += entryId
                    used += answer
                    return answer
                }
            }
            return null
        }

        private suspend fun inInbox(line: StatementLine.Read): String? {
            val key = "${line.amount.minor}|${line.direction.name}"
            val day = line.date.toEpochDay()
            return database.pendingTransactionDao().waitingInBucket(key)
                .filter { it.rawRefId?.startsWith(ownPrefix) != true && it.id !in used }
                .firstOrNull { row ->
                    val stated = ExtractedTransactionJson.decode(row.extractedJson)?.occurredAt ?: row.createdAt
                    kotlin.math.abs(LocalDates.of(stated).toLong() - day) <= 1
                }
                ?.id
                ?.also { used += it }
        }
    }

    internal companion object {
        const val REF_PREFIX: String = "import:"
        const val BATCH_PREFIX: String = "importBatch:"
        private const val DEFAULT_CURRENCY = "INR"

        fun referenceOf(table: StatementTable, line: StatementLine): String =
            "$REF_PREFIX${table.fingerprint}:${line.line}"
    }
}
