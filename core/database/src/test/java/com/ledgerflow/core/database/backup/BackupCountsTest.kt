package com.ledgerflow.core.database.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a restore from Settings counts on each side (ADR-0033 c): each book on
 * its own (Law 2), without the bin, and the Inbox exactly as its badge counts
 * it — so the backup's column and the phone's mean the same thing.
 */
class BackupCountsTest {

    private fun entry(id: String, ledger: String, deletedAt: Long? = null) = LedgerEntryRow(
        id = id, ledger = ledger, amountMinor = 100, currency = "INR",
        originalAmountMinor = null, originalCurrency = null, fxRateMicro = null,
        occurredAt = 0, localDate = 20261105, merchantId = null, categoryId = null,
        subcategoryId = null, paymentMethodId = null, note = null, source = "MANUAL",
        sourceRefId = null, isRecurring = false, createdAt = 0, updatedAt = 0, deletedAt = deletedAt,
    )

    private fun candidate(
        id: String,
        status: String = "PENDING",
        suppressedById: String? = null,
        nonTransactionKind: String? = null,
    ) = PendingTransactionRow(
        id = id, source = "SMS", dedupeKey = id, suppressedById = suppressedById, rawRefId = null,
        extractedJson = "{}", confidence = 0.5, status = status, needsManualFill = false,
        createdAt = 0, reviewedAt = null, approvedEntryId = null, nonTransactionKind = nonTransactionKind,
    )

    private fun payload(entries: List<LedgerEntryRow>, candidates: List<PendingTransactionRow>) = BackupPayload(
        schemaVersion = 13, createdAt = 0, appMeta = emptyList(), categories = emptyList(),
        merchants = emptyList(), paymentMethods = emptyList(), ledgerEntries = entries,
        lineItems = emptyList(), pendingTransactions = candidates,
    )

    @Test
    fun eachBookIsCountedOnItsOwn_andTheBinIsNotCounted() {
        val counts = payload(
            entries = listOf(
                entry("d1", "DEBIT"),
                entry("d2", "DEBIT"),
                entry("d3", "DEBIT", deletedAt = 5),
                entry("c1", "CREDIT"),
                entry("c2", "CREDIT", deletedAt = 5),
            ),
            candidates = emptyList(),
        ).counts()

        assertThat(counts).isEqualTo(BackupCounts(expenses = 2, income = 1, inbox = 0))
    }

    /** The badge's own rule (`observePendingCount`): pending, not suppressed, not "not a transaction". */
    @Test
    fun theInboxIsCountedAsItsBadgeCountsIt() {
        val counts = payload(
            entries = emptyList(),
            candidates = listOf(
                candidate("p1"),
                candidate("p2"),
                candidate("approved", status = "APPROVED"),
                candidate("discarded", status = "DISCARDED"),
                candidate("duplicate", suppressedById = "p1"),
                candidate("otp", nonTransactionKind = "OTP"),
            ),
        ).counts()

        assertThat(counts.inbox).isEqualTo(2)
    }
}
