package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An attachment's whole life: stored, linked to an entry, and unlinked when
 * that entry is destroyed (`docs/OCR-PIPELINE.md` steps 13 and 26).
 *
 * The two halves are one test class because they are one invariant with two
 * ends. Without the link, a receipt never reaches the entry it belongs to;
 * without the unlink, the purge takes the row and leaves the bytes forever —
 * and **nothing else in the app enumerates that directory**, so a leaked file
 * is invisible rather than merely untidy.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentLifecycleInstrumentedTest {

    private lateinit var vault: LedgerTestVault

    private val image = ByteArray(2_048) { (it % 241).toByte() }

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("attachment-lifecycle-test").apply { open() }
    }

    @After
    fun tearDown() {
        vault.close()
    }

    private fun files() = vault.attachmentFiles.all()

    private suspend fun storeImage(bytes: ByteArray = image): String =
        (vault.attachments.store(bytes, "image/png") as AttachmentOutcome.Stored).attachmentId

    private suspend fun anEntry(amount: Long = 69_446L): String = requireNotNull(
        vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(amount),
                occurredAt = 1_700_000_000_000L,
                assignment = EntryAssignment(),
            ),
        ).valueOrNull(),
    ).id

    /**
     * Step 26: approving a candidate attaches its image to the entry created.
     *
     * Driven through `markApproved`, which is where the link lives — in the
     * same transaction as the status change, because the two are one fact
     * about one approval.
     */
    @Test
    fun approvingACandidate_linksTheImageToTheEntry() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()

        // `raw_ref_id` is the attachment id for an OCR candidate.
        val pendingId = vault.seedPendingCandidate(rawRefId = attachmentId)
        assertThat(vault.pending.markApproved(pendingId, entryId)).isTrue()

        assertThat(vault.database.attachmentDao().byId(attachmentId)?.entryId)
            .isEqualTo(entryId)
    }

    /**
     * A message candidate's `raw_ref_id` names no attachment, so the same
     * unconditional link touches nothing.
     *
     * This is what makes the link source-agnostic rather than an
     * `if (source == OCR)` — and it is the assertion that would catch the
     * update being written against the wrong column.
     */
    @Test
    fun approvingAMessageCandidate_linksNothing() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()

        val pendingId = vault.seedPendingCandidate(rawRefId = "sms-raw-row-id")
        assertThat(vault.pending.markApproved(pendingId, entryId)).isTrue()

        assertThat(vault.database.attachmentDao().byId(attachmentId)?.entryId).isNull()
    }

    /**
     * **The obligation `AttachmentDao` records and nothing honoured until now.**
     *
     * `ON DELETE CASCADE` removes the row; the bytes are the caller's problem.
     * Nothing else enumerates `filesDir/attachments/`, so a file missed here
     * is leaked permanently and no surface would ever show it.
     */
    @Test
    fun purgingAnEntry_unlinksItsImage() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()
        vault.database.attachmentDao().linkToEntry(attachmentId, entryId)
        assertThat(files()).hasSize(1)

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entryId)
        val purged = vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)

        assertThat(purged).isEqualTo(1)
        assertThat(vault.database.attachmentDao().byId(attachmentId)).isNull()
        assertThat(files()).isEmpty()
    }

    /** The per-row purge carries the same obligation. */
    @Test
    fun purgingOneEntry_unlinksOnlyItsOwnImage() = runTest {
        val keptId = storeImage(ByteArray(1_024) { 7 })
        val doomedId = storeImage(ByteArray(1_024) { 9 })
        val keptEntry = anEntry()
        val doomedEntry = anEntry(amount = 1_000L)
        vault.database.attachmentDao().linkToEntry(keptId, keptEntry)
        vault.database.attachmentDao().linkToEntry(doomedId, doomedEntry)

        vault.ledger.softDeleteEntry(LedgerType.DEBIT, doomedEntry)
        vault.ledger.purgeDeletedEntry(LedgerType.DEBIT, doomedEntry)

        assertThat(vault.database.attachmentDao().byId(doomedId)).isNull()
        assertThat(vault.database.attachmentDao().byId(keptId)).isNotNull()
        assertThat(files()).hasSize(1)
    }

    /**
     * A purge that affects no rows unlinks nothing.
     *
     * The statements bind `:ledger` and `deleted_at IS NOT NULL`, so they
     * legitimately match nothing — and unlinking on that path would destroy
     * the image of a **live** entry. The most dangerous line in this change is
     * the one that decides whether the delete happened.
     */
    @Test
    fun purgingALiveEntry_destroysNothing() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()
        vault.database.attachmentDao().linkToEntry(attachmentId, entryId)

        // Never soft-deleted, so the purge's predicate excludes it.
        val purged = vault.ledger.purgeDeletedEntry(LedgerType.DEBIT, entryId)

        assertThat(purged).isEqualTo(0)
        assertThat(vault.database.attachmentDao().byId(attachmentId)).isNotNull()
        assertThat(files()).hasSize(1)
    }

    /**
     * An unattached image survives a purge.
     *
     * A candidate scanned and never approved owns an image with a null
     * `entry_id`. It belongs to no entry, so no entry's destruction may take
     * it — the user still has it in their Inbox.
     */
    @Test
    fun anUnapprovedCandidatesImage_survivesAPurge() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entryId)

        vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)

        assertThat(vault.database.attachmentDao().byId(attachmentId)).isNotNull()
        assertThat(files()).hasSize(1)
    }

    /**
     * The vault is still readable after the purge, which runs `VACUUM`.
     *
     * `PurgeDeletedEntriesTest` already makes this point for the database; it
     * matters again here because the unlink now runs on the same path, and a
     * mistake in file handling that somehow took the database with it would
     * surface as an unreadable vault on the user's next launch.
     */
    @Test
    fun theVaultIsStillReadableAfterAPurgeThatUnlinked() = runTest {
        val attachmentId = storeImage()
        val entryId = anEntry()
        vault.database.attachmentDao().linkToEntry(attachmentId, entryId)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, entryId)

        vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)

        // A read that would throw on a damaged file.
        assertThat(vault.database.attachmentDao().all()).isEmpty()
        assertThat(vault.ledger.baseCurrency()).isEqualTo(LedgerTestVault.BASE_CURRENCY)
    }
}
