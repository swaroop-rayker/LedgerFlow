package com.ledgerflow.core.data.recurring

import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.RecurringMatchRow
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.recurring.RecurringSchedule
import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus

/**
 * Whether something captured already answers an item's due date (V2-PLAN
 * §4.2; owner 2026-11-09). Its own decision, as a statement import makes its
 * own: the capture dedupe compares candidates written minutes apart, and a
 * due date is days wide.
 *
 * **What counts:** in the item's book only (Law 2), dated from three days
 * before the due date to two after; the same amount, unless the item's amount
 * varies; and the same merchant whenever both sides know theirs. An item whose
 * amount varies needs its merchant to match, or anything of any amount would.
 *
 * **Where it looks:** the book's entries first, then candidates still waiting
 * in the Inbox (not suppressed, not a non-transaction, not another recurring
 * candidate). **One answer per due date:** an entry or candidate another due
 * date already claimed is passed over, and so is an entry approved from a
 * claimed candidate.
 */
internal class RecurringMatcher(private val database: LedgerFlowDatabase) {

    private val dao = database.recurringDao()

    suspend fun match(item: RecurringItem, dueDate: Int): RecurringMatch? {
        val from = dueDate - RecurringSchedule.MATCH_DAYS_BEFORE
        val to = dueDate + RecurringSchedule.MATCH_DAYS_AFTER
        val claimedPending = dao.claimedPendingIds().toSet()
        val claimedEntries = dao.claimedEntryIds().toSet()
        return inBook(item, from, to, claimedEntries, claimedPending) ?: inInbox(item, from, to, claimedPending)
    }

    private suspend fun inBook(
        item: RecurringItem,
        from: Int,
        to: Int,
        claimedEntries: Set<String>,
        claimedPending: Set<String>,
    ): RecurringMatch? {
        val rows: List<RecurringMatchRow> = when (item.ledger) {
            LedgerType.DEBIT -> dao.debitsBetween(from, to)
            LedgerType.CREDIT -> dao.creditsBetween(from, to)
        }
        return rows.firstOrNull { row ->
            row.id !in claimedEntries &&
                answers(item, row.amountMinor, row.merchantId) &&
                database.pendingTransactionDao().idApprovedAs(row.id) !in claimedPending
        }?.let { RecurringMatch.Entry(it.id) }
    }

    private suspend fun inInbox(item: RecurringItem, from: Int, to: Int, claimedPending: Set<String>): RecurringMatch? {
        val direction = if (item.ledger == LedgerType.DEBIT) ExtractedDirection.DEBIT else ExtractedDirection.CREDIT
        return database.pendingTransactionDao().withStatus(PendingStatus.PENDING, INBOX_LIMIT)
            .asSequence()
            .filter { row ->
                row.suppressedById == null &&
                    row.nonTransactionKind == null &&
                    row.id !in claimedPending &&
                    row.rawRefId?.startsWith(DefaultRecurringRepository.REF_PREFIX) != true
            }
            .firstOrNull { row ->
                val extracted = ExtractedTransactionJson.decode(row.extractedJson) ?: return@firstOrNull false
                val day = LocalDates.of(extracted.occurredAt ?: row.createdAt)
                extracted.direction == direction &&
                    day in from..to &&
                    answers(item, extracted.amount, merchantOf(extracted.merchantRaw))
            }
            ?.let { RecurringMatch.Candidate(it.id) }
    }

    /** The amount and merchant rules above, for one entry or candidate. */
    private fun answers(item: RecurringItem, amount: Money?, merchantId: String?): Boolean {
        val amountOk = item.amountVaries || amount == item.amount
        val merchantOk = when {
            item.amountVaries -> item.merchantId != null && merchantId == item.merchantId
            item.merchantId == null || merchantId == null -> true
            else -> merchantId == item.merchantId
        }
        return amountOk && merchantOk
    }

    /** What a captured name resolves to, as the merchant picker resolves it: the name, then a taught alias. */
    private suspend fun merchantOf(raw: String?): String? {
        val key = raw?.let(MerchantNormalizer::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return database.merchantDao().byNormalizedKey(key)?.id
            ?: database.merchantAliasDao().byNormalized(key)?.merchantId
    }

    private companion object {
        /** The Inbox rarely holds this many; a bound so a pathological one cannot stall the pass. */
        const val INBOX_LIMIT = 500
    }
}
