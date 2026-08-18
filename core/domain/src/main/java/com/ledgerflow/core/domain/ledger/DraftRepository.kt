package com.ledgerflow.core.domain.ledger

import com.ledgerflow.core.model.LedgerType

/**
 * In-flight entry-form state (SPEC.md §6.1.2, D-06) — BUG6's countermeasure.
 *
 * **Not the ledger.** Nothing here has been saved by the user, nothing here is
 * visible to any ledger query, and nothing here counts towards a total. It
 * exists so a process death between the first keystroke and the Save tap costs
 * the user nothing.
 *
 * The payload is an opaque string at this layer. Its shape is the entry form's
 * business, and `:core:domain` knowing the field names of a screen would invert
 * the dependency the module graph exists to keep pointing one way.
 */
public interface DraftRepository {

    /**
     * The draft occupying [slot], or null.
     *
     * A row whose [EntryDraft.payloadVersion] this build does not understand is
     * still returned — callers ask it for its payload via
     * [EntryDraft.payloadIfReadable] and get null. Filtering it out here would
     * make it indistinguishable from "no draft", and the next save would upsert
     * over the slot and destroy it. §6.1.2 is explicit: the app does not
     * destroy user input to tidy up after itself.
     */
    public suspend fun find(slot: DraftSlot): EntryDraft?

    /**
     * Writes [slot]'s draft, creating it on first call.
     *
     * A single-row upsert, because the entry form calls this on every field
     * change behind a 300 ms debounce and it is the hottest write path in the
     * app. Anything multi-row here lands on StrictMode's `penaltyDeath`
     * tripwire (§11).
     */
    public suspend fun save(
        slot: DraftSlot,
        payloadJson: String,
        payloadVersion: Int,
    ): EntryDraft

    /**
     * Removes [slot]'s draft.
     *
     * Called when the entry is saved, and when the user explicitly chooses to
     * start fresh. Never called to tidy up — see [purgeAbandoned] for the one
     * unattended deletion in this table.
     */
    public suspend fun discard(slot: DraftSlot)

    /**
     * Deletes drafts untouched for [RETENTION_MILLIS] (§6.1.2).
     *
     * The app was killed and the user never came back. One `DELETE` on app
     * open, so the table cannot grow into a list nobody curates.
     */
    public suspend fun purgeAbandoned(): Int

    public companion object {
        private const val DAYS_RETAINED = 30L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** Thirty days, per §6.1.2. */
        public const val RETENTION_MILLIS: Long = DAYS_RETAINED * MILLIS_PER_DAY
    }
}

/**
 * Which form a draft belongs to (`UNIQUE(ledger, editing_entry_key)`).
 *
 * Uniqueness is scoped rather than unlimited: one new-entry draft per ledger —
 * the two books have separate forms — and one edit-draft per existing entry.
 * Unbounded drafts would accumulate into a list nobody curates; a singleton
 * would silently destroy the first draft when a second was started, which is
 * BUG6 reintroduced by BUG6's own countermeasure.
 */
public data class DraftSlot(
    val ledger: LedgerType,
    /** Null for a new entry; set when this draft is an in-flight edit. */
    val editingEntryId: String? = null,
)

/** A persisted form-in-progress. */
public data class EntryDraft(
    val id: String,
    val slot: DraftSlot,
    val payloadJson: String,
    val payloadVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * The payload, if this build can read it.
     *
     * The version check lives here rather than at each call site so it cannot be
     * forgotten: a payload written by a newer build must never be deserialized
     * against an older schema, because the failure mode is not an exception but
     * a form that silently comes back with fields missing.
     */
    public fun payloadIfReadable(supportedVersion: Int): String? =
        payloadJson.takeIf { payloadVersion == supportedVersion }
}
