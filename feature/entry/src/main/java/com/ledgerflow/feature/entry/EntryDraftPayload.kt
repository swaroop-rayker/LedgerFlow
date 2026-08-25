package com.ledgerflow.feature.entry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * The entry form, as it is stored in `draft_entry.payload_json` (§6.1.2).
 *
 * JSON rather than typed columns because a draft is partial and invalid by
 * definition -- an amount mid-keystroke, no category chosen, a line item with a
 * blank name -- so every typed column would have to be nullable and would
 * forfeit the constraint value that motivated typing them. It is also never
 * queried by any dimension, and the multi-line editor would otherwise make each
 * 300 ms debounce tick a multi-row transaction instead of a single-row upsert.
 *
 * **`ledger` is deliberately absent.** It is a real column on `draft_entry` and
 * is authoritative there, so the two cannot disagree about which book a draft
 * belongs to (§6.1.2).
 *
 * Every field is optional with a default. A payload is a snapshot of a form
 * mid-edit; adding a field to the form must not make yesterday's drafts
 * unreadable, and a default is what lets [VERSION] stay put for an additive
 * change.
 */
@Serializable
internal data class EntryDraftPayload(
    val amountMinor: Long = 0L,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
    val note: String = "",
    val occurredAt: Long = 0L,
    /** `Single item | Itemised` (ADR-0018). Absent in payloads written before it. */
    val itemised: Boolean = false,
    val lineItems: List<DraftLineItem> = emptyList(),
)

/**
 * One line of an in-flight itemised entry.
 *
 * Every field added for ADR-0018 is optional with a default, so a draft written
 * before it still reads: no [VERSION] bump, and nobody's half-typed entry is
 * orphaned by the feature landing. [amountMinor] is the pre-ADR field and is
 * what such a draft carries instead of a unit price; `toLine` treats it as the
 * unit price at quantity one, which is exactly what it meant.
 */
@Serializable
internal data class DraftLineItem(
    val key: String,
    val name: String = "",
    /** Derived (`unit price x quantity`). Written for self-description, read only as a fallback. */
    val amountMinor: Long = 0L,
    val unitPriceMinor: Long = 0L,
    val quantityMilli: Long = 1_000L,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
)

/**
 * Reads and writes [EntryDraftPayload].
 *
 * [VERSION] is bumped only when a change would make an old payload *wrong*
 * rather than merely incomplete -- a renamed field, a changed unit. Adding an
 * optional field is not that, because `ignoreUnknownKeys` and the defaults
 * above already handle it in both directions.
 *
 * A payload that will not parse returns null rather than throwing. §6.1.2 is
 * explicit that the row is retained either way: the app does not destroy user
 * input to tidy up after itself, and a draft it cannot read is still evidence
 * of work someone did.
 */
internal object EntryDraftCodec {

    /** The current form-state format. Stored in `draft_entry.payload_version`. */
    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: EntryDraftPayload): String = json.encodeToString(payload)

    fun decode(payloadJson: String): EntryDraftPayload? =
        try {
            json.decodeFromString<EntryDraftPayload>(payloadJson)
        } catch (e: SerializationException) {
            // Deliberately not rethrown and deliberately not silent-by-omission:
            // a corrupt payload means the form starts empty, which is the only
            // safe reading, while the row stays on disk for a diagnostics
            // export to find.
            android.util.Log.w("EntryDraftCodec", "Unreadable draft payload; starting empty", e)
            null
        }
}
