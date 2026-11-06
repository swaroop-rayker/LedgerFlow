package com.ledgerflow.core.domain.statement

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.inbox.ReviewEdits
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import org.junit.Test

/**
 * D8's "only complete rows qualify", and the panel's figures, which the
 * confirmation repeats: each book counted and totalled on its own (Law 2).
 */
class ImportBatchesTest {

    private fun row(
        id: String = "r",
        direction: ExtractedDirection = ExtractedDirection.DEBIT,
        minor: Long? = 41_000,
        currency: String? = "INR",
        occurredAt: Long? = 1_791_000_000_000,
    ) = PendingTransaction(
        id = id,
        source = EntrySource.IMPORT,
        extracted = ExtractedTransaction(
            amount = minor?.let(::Money),
            currency = currency,
            direction = direction,
            merchantRaw = "SWIGGY",
            occurredAt = occurredAt,
            confidence = 1.0,
        ),
        confidence = 1.0,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_791_000_000_000,
        reviewedAt = null,
        approvedEntryId = null,
    )

    private fun ready(row: PendingTransaction) = ImportBatches.isReady(row, baseCurrency = "INR")

    @Test
    fun aCompleteImportedRowInTheBaseCurrency_isReady() {
        assertThat(ready(row())).isTrue()
        assertThat(ready(row(direction = ExtractedDirection.CREDIT))).isTrue()
        // No currency column: the base currency, as the import read it.
        assertThat(ready(row(currency = null))).isTrue()
    }

    @Test
    fun anIncompleteRow_staysForReview() {
        assertThat(ready(row(minor = null))).isFalse()
        assertThat(ready(row(direction = ExtractedDirection.UNKNOWN))).isFalse()
        assertThat(ready(row(occurredAt = null))).isFalse()
        assertThat(ready(row().copy(needsManualFill = true))).isFalse()
        // A foreign amount needs its base amount entered at review.
        assertThat(ready(row(currency = "EUR"))).isFalse()
        // The user has started reviewing it: theirs to finish.
        assertThat(ready(row().copy(edits = ReviewEdits(noteText = "dinner")))).isFalse()
    }

    /** D8: never SMS or notification candidates, and nothing already decided. */
    @Test
    fun otherSourcesAndDecidedRows_areNeverReady() {
        assertThat(ready(row().copy(source = EntrySource.SMS))).isFalse()
        assertThat(ready(row().copy(source = EntrySource.NOTIFICATION))).isFalse()
        assertThat(ready(row().copy(status = PendingStatus.APPROVED))).isFalse()
        assertThat(ready(row().copy(status = PendingStatus.DISCARDED))).isFalse()
        assertThat(ready(row().copy(suppressedById = "other"))).isFalse()
    }

    @Test
    fun summary_countsAndTotalsEachBookOnItsOwn() {
        val waiting = ImportWaiting(
            baseCurrency = "INR",
            rows = listOf(
                row("a", minor = 41_000),
                row("b", minor = 186_240),
                row("c", direction = ExtractedDirection.CREDIT, minor = 8_500_000),
                row("d", currency = "EUR", minor = 12_000),
            ),
        )

        val batch = requireNotNull(ImportBatches.summarise("fp", ImportNote("oct.csv", 7L), waiting))

        assertThat(batch.spent).isEqualTo(BookShare(2, Money(227_240)))
        assertThat(batch.received).isEqualTo(BookShare(1, Money(8_500_000)))
        assertThat(batch.toReview).isEqualTo(1)
        assertThat(batch.ready).isEqualTo(3)
        assertThat(batch.fileName to batch.importedAt).isEqualTo("oct.csv" to 7L)
    }

    @Test
    fun anImportWithNothingWaiting_hasNoPanel() {
        assertThat(ImportBatches.summarise("fp", null, ImportWaiting("INR", emptyList()))).isNull()
    }
}
