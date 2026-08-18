package com.ledgerflow.core.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSlot
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `draft_entry` (SPEC.md §6.1.2, D-06) — the storage half of BUG6.
 *
 * The screen-level regression test lives in `:feature:entry`
 * (`Bug6_DraftSurvivesProcessDeathTest`) and kills a real process. This one
 * covers what that test depends on being true: that a slot holds exactly one
 * draft, that resuming finds it, and that nothing here ever deletes user input
 * except on the user's own instruction or the 30-day sweep.
 */
@RunWith(AndroidJUnit4::class)
class DraftRepositoryInstrumentedTest {

    private val vault = LedgerTestVault("lf_draft_test")

    private val newDebit = DraftSlot(LedgerType.DEBIT)
    private val newCredit = DraftSlot(LedgerType.CREDIT)

    @Before
    fun setUp() = runBlocking<Unit> { vault.open() }

    @After
    fun tearDown() = vault.close()

    @Test
    fun save_thenFind_returnsThePayloadVerbatim() = runBlocking<Unit> {
        vault.drafts.save(newDebit, PAYLOAD, VERSION)

        val found = vault.drafts.find(newDebit)

        assertThat(found).isNotNull()
        assertThat(found?.payloadJson).isEqualTo(PAYLOAD)
        assertThat(found?.slot).isEqualTo(newDebit)
    }

    @Test
    fun find_emptySlot_isNull() = runBlocking<Unit> {
        assertThat(vault.drafts.find(newDebit)).isNull()
    }

    /**
     * The debounce writes on every keystroke, so this is the hot path: repeated
     * saves must land on one row rather than accumulate siblings that the unique
     * index would eventually reject mid-typing.
     */
    @Test
    fun save_repeatedly_keepsOneRowAndOneId() = runBlocking<Unit> {
        val first = vault.drafts.save(newDebit, """{"amount":"1"}""", VERSION)
        vault.now += 500L
        val second = vault.drafts.save(newDebit, """{"amount":"12"}""", VERSION)
        vault.now += 500L
        val third = vault.drafts.save(newDebit, """{"amount":"125"}""", VERSION)

        assertThat(second.id).isEqualTo(first.id)
        assertThat(third.id).isEqualTo(first.id)
        assertThat(vault.session.requireDatabase().draftEntryDao().all()).hasSize(1)
        assertThat(vault.drafts.find(newDebit)?.payloadJson).isEqualTo("""{"amount":"125"}""")
    }

    /** `created_at` is when the user started, not when they last typed. */
    @Test
    fun save_preservesCreatedAtAndAdvancesUpdatedAt() = runBlocking<Unit> {
        val first = vault.drafts.save(newDebit, PAYLOAD, VERSION)
        vault.now += 10_000L
        val second = vault.drafts.save(newDebit, PAYLOAD, VERSION)

        assertThat(second.createdAt).isEqualTo(first.createdAt)
        assertThat(second.updatedAt).isGreaterThan(first.updatedAt)
    }

    /**
     * D-06: the two books have separate forms, so a debit draft and a credit
     * draft coexist. A singleton would silently destroy the first when the
     * second was started — BUG6 reintroduced by BUG6's own countermeasure.
     */
    @Test
    fun save_bothLedgers_occupySeparateSlots() = runBlocking<Unit> {
        vault.drafts.save(newDebit, """{"which":"debit"}""", VERSION)
        vault.drafts.save(newCredit, """{"which":"credit"}""", VERSION)

        assertThat(vault.drafts.find(newDebit)?.payloadJson).isEqualTo("""{"which":"debit"}""")
        assertThat(vault.drafts.find(newCredit)?.payloadJson).isEqualTo("""{"which":"credit"}""")
        assertThat(vault.session.requireDatabase().draftEntryDao().all()).hasSize(2)
    }

    /**
     * `editing_entry_key` is `COALESCE(editing_entry_id, '')`, so an edit-draft
     * lands in its own slot rather than colliding with the new-entry one.
     */
    @Test
    fun save_editDraft_doesNotDisplaceTheNewEntryDraft() = runBlocking<Unit> {
        val entryId = approveAnEntry()

        vault.drafts.save(newDebit, """{"which":"new"}""", VERSION)
        val editSlot = DraftSlot(LedgerType.DEBIT, editingEntryId = entryId)
        vault.drafts.save(editSlot, """{"which":"edit"}""", VERSION)

        assertThat(vault.drafts.find(newDebit)?.payloadJson).isEqualTo("""{"which":"new"}""")
        assertThat(vault.drafts.find(editSlot)?.payloadJson).isEqualTo("""{"which":"edit"}""")
        assertThat(vault.drafts.find(editSlot)?.slot?.editingEntryId).isEqualTo(entryId)
    }

    @Test
    fun discard_removesOnlyThatSlot() = runBlocking<Unit> {
        vault.drafts.save(newDebit, PAYLOAD, VERSION)
        vault.drafts.save(newCredit, PAYLOAD, VERSION)

        vault.drafts.discard(newDebit)

        assertThat(vault.drafts.find(newDebit)).isNull()
        assertThat(vault.drafts.find(newCredit)).isNotNull()
    }

    @Test
    fun discard_emptySlot_isHarmless() = runBlocking<Unit> {
        vault.drafts.discard(newDebit)

        assertThat(vault.drafts.find(newDebit)).isNull()
    }

    /**
     * §6.1.2: a payload this build cannot read is **kept**, not deleted and not
     * hidden. Filtering it out here would make it indistinguishable from "no
     * draft", and the next save would upsert straight over it.
     */
    @Test
    fun find_unreadableVersion_returnsTheRowButNotThePayload() = runBlocking<Unit> {
        vault.drafts.save(newDebit, PAYLOAD, payloadVersion = VERSION + 1)

        val found = vault.drafts.find(newDebit)

        assertThat(found).isNotNull()
        assertThat(found?.payloadIfReadable(VERSION)).isNull()
        assertThat(found?.payloadIfReadable(VERSION + 1)).isEqualTo(PAYLOAD)
    }

    @Test
    fun purgeAbandoned_removesOnlyDraftsPastRetention() = runBlocking<Unit> {
        vault.now = 1_000_000L
        vault.drafts.save(newDebit, PAYLOAD, VERSION)

        vault.now += DraftRepository.RETENTION_MILLIS + 1
        vault.drafts.save(newCredit, PAYLOAD, VERSION)

        val purged = vault.drafts.purgeAbandoned()

        assertThat(purged).isEqualTo(1)
        assertThat(vault.drafts.find(newDebit)).isNull()
        assertThat(vault.drafts.find(newCredit)).isNotNull()
    }

    @Test
    fun purgeAbandoned_freshDrafts_isANoOp() = runBlocking<Unit> {
        vault.now = 1_000_000L
        vault.drafts.save(newDebit, PAYLOAD, VERSION)

        assertThat(vault.drafts.purgeAbandoned()).isEqualTo(0)
        assertThat(vault.drafts.find(newDebit)).isNotNull()
    }

    /**
     * `editing_entry_id` carries `ON DELETE CASCADE`, so an edit-draft cannot
     * outlive the entry it is editing and become a form pointing at nothing.
     */
    @Test
    fun editDraft_isCascadedAwayWithItsEntry() = runBlocking<Unit> {
        val entryId = approveAnEntry()
        val editSlot = DraftSlot(LedgerType.DEBIT, editingEntryId = entryId)
        vault.drafts.save(editSlot, PAYLOAD, VERSION)

        vault.session.requireDatabase()
            .openHelper.writableDatabase
            .execSQL("DELETE FROM ledger_entry WHERE id = ?", arrayOf(entryId))

        assertThat(vault.drafts.find(editSlot)).isNull()
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
