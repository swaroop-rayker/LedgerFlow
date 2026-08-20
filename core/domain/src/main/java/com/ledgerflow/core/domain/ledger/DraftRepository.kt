package com.ledgerflow.core.domain.ledger

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * In-flight entry-form state (SPEC.md §6.1.2) — BUG6's countermeasure.
 *
 * **Not the ledger.** Nothing here has been saved by the user, nothing here is
 * visible to any ledger query, and nothing here counts towards a total. It
 * exists so a process death between the first keystroke and the Save tap costs
 * the user nothing.
 *
 * **A book may hold many drafts (ADR-0013, superseding D-06.)** The original
 * design allowed exactly one new-entry draft per ledger, enforced by a unique
 * index, on the reasoning that unbounded drafts would accumulate where nobody
 * would curate them. That reasoning was sound about the risk and wrong about
 * the remedy: in use, starting a second entry silently resumed the first, so
 * the second could not exist and the first appeared to have eaten it. The
 * answer to "nobody can find them" is [observe] and a surface that shows them.
 *
 * The payload is an opaque string at this layer. Its shape is the entry form's
 * business, and `:core:domain` knowing the field names of a screen would invert
 * the dependency the module graph exists to keep pointing one way.
 */
public interface DraftRepository {

    /**
     * One book's unsaved entries, most recently touched first.
     *
     * The ordering is the stack the user sees. Rows whose
     * [EntryDraft.payloadVersion] this build cannot read are still emitted —
     * see [EntryDraft.payloadIfReadable]. Hiding them would make them
     * indistinguishable from "no draft" and invite the next save to overwrite
     * one, and §6.1.2 is explicit that the app does not destroy user input to
     * tidy up after itself.
     */
    public fun observe(ledger: LedgerType): Flow<List<EntryDraft>>

    public suspend fun find(id: String): EntryDraft?

    /**
     * The in-flight edit of [editingEntryId], if one exists.
     *
     * One edit-draft per entry is still the rule — you cannot be editing the
     * same entry twice — but it is a repository rule now rather than a unique
     * index, because a partial index (`WHERE editing_entry_id IS NOT NULL`) is
     * not expressible through Room's `@Index` and a full one would be the
     * constraint ADR-0013 removes.
     */
    public suspend fun findForEntry(ledger: LedgerType, editingEntryId: String): EntryDraft?

    /**
     * Creates or updates one draft.
     *
     * A single-row upsert, because the entry form calls this on every field
     * change behind a 300 ms debounce and it is the hottest write in the app.
     * Anything multi-row here lands on StrictMode's `penaltyDeath` tripwire
     * (§11).
     */
    public suspend fun save(draft: DraftWrite): EntryDraft

    /**
     * Removes one draft.
     *
     * Called when its entry is saved, and when the user explicitly discards it.
     * Never called to tidy up — see [purgeAbandoned] for the one unattended
     * deletion in this table.
     */
    public suspend fun discard(id: String)

    /**
     * Deletes drafts untouched for [RETENTION_MILLIS] (§6.1.2).
     *
     * The app was killed and the user never came back. One `DELETE` on app
     * open. This is what keeps "many drafts" from becoming "drafts forever".
     */
    public suspend fun purgeAbandoned(): Int

    /**
     * One book's unsaved entries as the Ledger shows them: an amount and the
     * names it is filed under, no payload.
     *
     * Separate from [observe] because the two callers want different things.
     * The entry form wants the payload so it can restore a half-typed form;
     * the Ledger wants something renderable and must never see the payload at
     * all -- its shape is `:feature:entry`'s business, and a second feature
     * parsing it would couple two features through a JSON schema.
     *
     * Names are resolved by the query, and are null when the category or
     * merchant has since been deleted. That is the honest rendering of "you
     * picked something that has gone", and it is why the summary columns carry
     * no foreign keys.
     */
    public fun observeSummaries(ledger: LedgerType): Flow<List<DraftSummary>>

    public companion object {
        private const val DAYS_RETAINED = 30L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** Thirty days, per §6.1.2. */
        public const val RETENTION_MILLIS: Long = DAYS_RETAINED * MILLIS_PER_DAY
    }
}

/**
 * A draft to write.
 *
 * [id] null means "a new one". The caller holds the id it gets back and passes
 * it on subsequent saves, which is what makes a form's debounce update one row
 * rather than deposit a new draft every 300 ms.
 */
public data class DraftWrite(
    val id: String?,
    val ledger: LedgerType,
    /** Null for a new entry; set when this draft is an in-flight edit. */
    val editingEntryId: String? = null,
    val payloadJson: String,
    val payloadVersion: Int,
    /**
     * A typed summary of what is inside [payloadJson], lifted out by the
     * writer (schema v4).
     *
     * The caller supplies it because the caller is the only thing that can:
     * the payload's shape belongs to the entry form, and every layer below
     * this one treats the JSON as opaque. [payloadJson] stays authoritative --
     * if the two ever disagree, the payload is right and the summary is a
     * stale render.
     */
    val summary: DraftSummaryFields = DraftSummaryFields(),
)

/**
 * The part of a draft that other screens are allowed to know about.
 *
 * Deliberately not the whole form. A draft is `:feature:entry`'s working
 * state; this is the minimum another surface needs to say "you have an unsaved
 * ₹240 at Zepto" and offer to open it.
 */
public data class DraftSummaryFields(
    /** Minor units (Law 3). Zero while nothing has been typed, which is common. */
    val amountMinor: Long = 0L,
    val categoryId: String? = null,
    val merchantId: String? = null,
    /**
     * When the form says the entry happened (schema v5).
     *
     * The date the user picked, not when they last typed -- so a pending row
     * reads the same way a committed one does. Zero means "not recorded",
     * which is every draft written before v5.
     */
    val occurredAt: Long = 0L,
)

/**
 * An unsaved entry, as the Ledger's pending section renders it.
 *
 * [categoryName] and [merchantName] are resolved by the query rather than held
 * on the draft, so a category renamed after the draft was written shows its
 * current name. They are null when the row they pointed at is gone.
 */
public data class DraftSummary(
    val id: String,
    val ledger: LedgerType,
    val amount: Money,
    val categoryName: String?,
    val categoryColorArgb: Int?,
    val merchantName: String?,
    val updatedAt: Long,
    /**
     * When to say this entry happened.
     *
     * Already resolved: the draft's own `occurred_at` when it has one, and its
     * last edit when it does not (a draft written before schema v5). The screen
     * never has to know which it got.
     */
    val datedAt: Long,
)

/** A persisted form-in-progress. */
public data class EntryDraft(
    val id: String,
    val ledger: LedgerType,
    val editingEntryId: String?,
    val payloadJson: String,
    val payloadVersion: Int,
    /** The denormalised copy written alongside [payloadJson] (schema v4). */
    val summary: DraftSummaryFields = DraftSummaryFields(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * The payload, if this build can read it.
     *
     * The version check lives here rather than at each call site so it cannot
     * be forgotten: a payload written by a newer build must never be
     * deserialized against an older schema, because the failure is not an
     * exception but a form that quietly comes back with fields missing.
     */
    public fun payloadIfReadable(supportedVersion: Int): String? =
        payloadJson.takeIf { payloadVersion == supportedVersion }
}
