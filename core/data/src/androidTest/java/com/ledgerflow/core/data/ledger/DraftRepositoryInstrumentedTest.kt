package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftWrite
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `draft_entry` (SPEC.md §6.1.2) — the storage half of BUG6.
 *
 * The screen-level regression test lives in `:app`
 * (`Bug6_DraftSurvivesProcessDeathTest`) and rebuilds the whole graph from
 * disk. This one covers what that test depends on: that a draft's identity is
 * its id, that a book can hold several at once (ADR-0013), and that nothing
 * here deletes user input except on the user's instruction or the 30-day sweep.
 */
@RunWith(AndroidJUnit4::class)
class DraftRepositoryInstrumentedTest {

    private val vault = LedgerTestVault("lf_draft_test")

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    private suspend fun write(
        id: String? = null,
        ledger: LedgerType = LedgerType.DEBIT,
        payload: String = PAYLOAD,
        version: Int = VERSION,
        editingEntryId: String? = null,
    ) = vault.drafts.save(
        DraftWrite(
            id = id,
            ledger = ledger,
            editingEntryId = editingEntryId,
            payloadJson = payload,
            payloadVersion = version,
        ),
    )

    @Test
    fun save_thenFind_returnsThePayloadVerbatim() = runBlocking<Unit> {
        val saved = write()

        val found = vault.drafts.find(saved.id)

        assertThat(found?.payloadJson).isEqualTo(PAYLOAD)
        assertThat(found?.ledger).isEqualTo(LedgerType.DEBIT)
    }

    @Test
    fun find_unknownId_isNull() = runBlocking<Unit> {
        assertThat(vault.drafts.find("nope")).isNull()
    }

    /**
     * The debounce writes on every keystroke, so this is the hot path: passing
     * the id back must update one row. Without it the dropped unique index
     * (ADR-0013) would let the form deposit a fresh draft every 300 ms.
     */
    @Test
    fun save_withTheSameId_keepsOneRow() = runBlocking<Unit> {
        val first = write(payload = """{"amount":"1"}""")
        vault.now += 500L
        val second = write(id = first.id, payload = """{"amount":"12"}""")
        vault.now += 500L
        val third = write(id = first.id, payload = """{"amount":"125"}""")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(third.id).isEqualTo(first.id)
        assertThat(vault.session.requireDatabase().draftEntryDao().all()).hasSize(1)
        assertThat(vault.drafts.find(first.id)?.payloadJson).isEqualTo("""{"amount":"125"}""")
    }

    /** ADR-0013, superseding D-06: a book holds as many in-flight entries as you start. */
    @Test
    fun save_withoutAnId_addsAnotherDraftToTheSameBook() = runBlocking<Unit> {
        val first = write(payload = """{"which":"one"}""")
        vault.now += 500L
        val second = write(payload = """{"which":"two"}""")

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(vault.drafts.observe(LedgerType.DEBIT).first()).hasSize(2)
    }

    /** The stack is newest-first; that ordering is what the screen renders. */
    @Test
    fun observe_ordersMostRecentlyTouchedFirst() = runBlocking<Unit> {
        val older = write(payload = """{"which":"older"}""")
        vault.now += 1_000L
        val newer = write(payload = """{"which":"newer"}""")

        val stack = vault.drafts.observe(LedgerType.DEBIT).first()

        assertThat(stack.map { it.id }).containsExactly(newer.id, older.id).inOrder()
    }

    /** Law 2: one book's stack never shows the other's. */
    @Test
    fun observe_neverReturnsTheOtherBook() = runBlocking<Unit> {
        write(ledger = LedgerType.DEBIT, payload = """{"which":"debit"}""")
        write(ledger = LedgerType.CREDIT, payload = """{"which":"credit"}""")

        assertThat(vault.drafts.observe(LedgerType.DEBIT).first()).hasSize(1)
        assertThat(vault.drafts.observe(LedgerType.CREDIT).first()).hasSize(1)
        assertThat(vault.drafts.observe(LedgerType.DEBIT).first().single().payloadJson)
            .isEqualTo("""{"which":"debit"}""")
    }

    /** `created_at` is when the user started, not when they last typed. */
    @Test
    fun save_preservesCreatedAtAndAdvancesUpdatedAt() = runBlocking<Unit> {
        val first = write()
        vault.now += 10_000L
        val second = write(id = first.id)

        assertThat(second.createdAt).isEqualTo(first.createdAt)
        assertThat(second.updatedAt).isGreaterThan(first.updatedAt)
    }

    /**
     * One edit-draft per entry is a repository rule now rather than a unique
     * index, because a partial index is not expressible through Room.
     */
    @Test
    fun findForEntry_locatesTheEditDraft() = runBlocking<Unit> {
        val entryId = approveAnEntry()
        write(payload = """{"which":"new"}""")
        val edit = write(payload = """{"which":"edit"}""", editingEntryId = entryId)

        val found = vault.drafts.findForEntry(LedgerType.DEBIT, entryId)

        assertThat(found?.id).isEqualTo(edit.id)
        assertThat(found?.payloadJson).isEqualTo("""{"which":"edit"}""")
    }

    @Test
    fun discard_removesOnlyThatDraft() = runBlocking<Unit> {
        val doomed = write(payload = """{"which":"doomed"}""")
        val kept = write(payload = """{"which":"kept"}""")

        vault.drafts.discard(doomed.id)

        assertThat(vault.drafts.find(doomed.id)).isNull()
        assertThat(vault.drafts.find(kept.id)).isNotNull()
    }

    @Test
    fun discard_unknownId_isHarmless() = runBlocking<Unit> {
        vault.drafts.discard("nope")

        assertThat(vault.drafts.find("nope")).isNull()
    }

    /**
     * §6.1.2: a payload this build cannot read is **kept**, not deleted and not
     * hidden. Filtering it out here would make it indistinguishable from "no
     * draft", and the next save would upsert straight over it.
     */
    @Test
    fun find_unreadableVersion_returnsTheRowButNotThePayload() = runBlocking<Unit> {
        val saved = write(version = VERSION + 1)

        val found = vault.drafts.find(saved.id)

        assertThat(found).isNotNull()
        assertThat(found?.payloadIfReadable(VERSION)).isNull()
        assertThat(found?.payloadIfReadable(VERSION + 1)).isEqualTo(PAYLOAD)
    }

    /** The sweep is what keeps "many drafts" from becoming "drafts forever". */
    @Test
    fun purgeAbandoned_removesOnlyDraftsPastRetention() = runBlocking<Unit> {
        vault.now = 1_000_000L
        val old = write(payload = """{"which":"old"}""")

        vault.now += DraftRepository.RETENTION_MILLIS + 1
        val fresh = write(payload = """{"which":"fresh"}""")

        val purged = vault.drafts.purgeAbandoned()

        assertThat(purged).isEqualTo(1)
        assertThat(vault.drafts.find(old.id)).isNull()
        assertThat(vault.drafts.find(fresh.id)).isNotNull()
    }

    @Test
    fun purgeAbandoned_freshDrafts_isANoOp() = runBlocking<Unit> {
        vault.now = 1_000_000L
        val saved = write()

        assertThat(vault.drafts.purgeAbandoned()).isEqualTo(0)
        assertThat(vault.drafts.find(saved.id)).isNotNull()
    }

    /**
     * `editing_entry_id` carries `ON DELETE CASCADE`, so an edit-draft cannot
     * outlive the entry it is editing and become a form pointing at nothing.
     */
    @Test
    fun editDraft_isCascadedAwayWithItsEntry() = runBlocking<Unit> {
        val entryId = approveAnEntry()
        val edit = write(editingEntryId = entryId)

        vault.session.requireDatabase()
            .openHelper.writableDatabase
            .execSQL("DELETE FROM ledger_entry WHERE id = ?", arrayOf(entryId))

        assertThat(vault.drafts.find(edit.id)).isNull()
    }

    private suspend fun approveAnEntry(): String {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(100_00L),
                occurredAt = 1_700_000_000_000L,
                assignment = EntryAssignment(),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value.id
    }

    private companion object {
        private const val VERSION = 1
        private const val PAYLOAD = """{"amount":"125","note":"half typed"}"""
    }
}
