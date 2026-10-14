package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's "Recent spending" and "Recent income" read (V2-PLAN BUG-C), against a
 * real SQLCipher vault: the views' predicates, the `LIMIT` and the ordering are
 * SQL, so a fake would only assert the Kotlin.
 *
 * Every test that finds a row in one book also asserts it is absent from the
 * other, for the reason `Bug10_SavedEntryAppearsInLedgerTest` gives (Law 2).
 */
@RunWith(AndroidJUnit4::class)
class RecentEntriesReadTest {

    private val vault = LedgerTestVault("lf_recent_entries_test")

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    @Test
    fun recent_isNewestFirst_andLimited() = runBlocking<Unit> {
        val ids = (0 until 7).map { day -> approve(occurredAt = DAY_ONE + day * DAY) }

        val recent = vault.ledger.observeRecent(LedgerType.DEBIT, limit = 5).first()

        assertThat(recent.map { it.id }).containsExactlyElementsIn(ids.reversed().take(5)).inOrder()
    }

    @Test
    fun recent_isPerBook() = runBlocking<Unit> {
        val expense = approve()
        val income = approve(ledger = LedgerType.CREDIT)

        val debits = vault.ledger.observeRecent(LedgerType.DEBIT, limit = 5).first()
        val credits = vault.ledger.observeRecent(LedgerType.CREDIT, limit = 5).first()

        assertThat(debits.map { it.id }).containsExactly(expense)
        assertThat(credits.map { it.id }).containsExactly(income)
        assertThat(debits.map { it.ledger }.distinct()).containsExactly(LedgerType.DEBIT)
        assertThat(credits.map { it.ledger }.distinct()).containsExactly(LedgerType.CREDIT)
    }

    @Test
    fun aBinnedEntry_isNotRecent() = runBlocking<Unit> {
        val kept = approve(occurredAt = DAY_ONE)
        val binned = approve(occurredAt = DAY_ONE + DAY)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned)

        assertThat(vault.ledger.observeRecent(LedgerType.DEBIT, limit = 5).first().map { it.id })
            .containsExactly(kept)
    }

    /**
     * Home is open when an approval lands from a notification action, and no
     * resume follows. The read re-emitting is what updates the card.
     */
    @Test
    fun anApproval_reEmits() = runBlocking<Unit> {
        val next = async { vault.ledger.observeRecent(LedgerType.DEBIT, limit = 5).drop(1).first() }
        // Let the collector take its first emission before the write.
        delay(SETTLE_MILLIS)

        val entry = approve()

        assertThat(withTimeout(TIMEOUT_MILLIS) { next.await() }.map { it.id }).containsExactly(entry)
    }

    private suspend fun approve(
        ledger: LedgerType = LedgerType.DEBIT,
        occurredAt: Long = DAY_ONE,
    ): String {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = ledger,
                amount = Money(100_00L),
                occurredAt = occurredAt,
                assignment = EntryAssignment(),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value.id
    }

    private companion object {
        const val DAY_ONE = 1_700_000_000_000L
        const val DAY = 86_400_000L
        const val SETTLE_MILLIS = 200L
        const val TIMEOUT_MILLIS = 5_000L
    }
}
