package com.ledgerflow.feature.inbox

import com.ledgerflow.core.model.LedgerType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The review screen's in-progress typing, as it goes to disk (v8, BUG6).
 *
 * **The format belongs to this screen**, which is why it lives here and not in
 * `:core:domain` beside `ExtractedTransaction`. The distinction is the one
 * SPEC.md §6.1.2 already draws for `draft_entry.payload_json`: extraction
 * targets are spec-level and are decoded in `:core:data` so two layers cannot
 * disagree about them, whereas *form state* is one screen's business and giving
 * the domain an opinion about the review screen's field list would make every
 * UI change a schema conversation.
 *
 * **Raw text, not parsed values, wherever the user is typing.** [amountText]
 * and the lines' text fields are stored exactly as typed, for the same reason
 * `ReviewUiState` holds them that way: parsing to `Money` and formatting back
 * moves the caret, and a restored form that has quietly rewritten "12." as
 * "12.00" has edited the user's input while they were away.
 *
 * ## Versioning
 *
 * [version] is inside the envelope rather than in a column, exactly as
 * `extracted_json` does it — a `payload_version` column would be a schema bump
 * to carry an integer that fits in the JSON it describes. [json] ignores
 * unknown keys and every field defaults, so a payload written by a later build
 * and read by this one both survive.
 *
 * **An unreadable payload is discarded, not repaired.** [decode] returns null
 * and the screen opens from the parser's extraction as it did at v7 — the same
 * outcome as never having typed anything, which is the honest one. Guessing at
 * a half-understood payload would put fields in front of the user that they did
 * not enter.
 */
@Serializable
internal data class ReviewDraftPayload(
    val version: Int = VERSION,
    val ledger: String? = null,
    val amountText: String = "",
    val occurredAt: Long? = null,
    val noteText: String = "",
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
    val itemised: Boolean = false,
    val lines: List<Line> = emptyList(),
) {

    /** One itemised line, as typed. */
    @Serializable
    internal data class Line(
        val key: String = "",
        val name: String = "",
        val unitPriceText: String = "",
        val unitPriceMinor: Long = 0L,
        val quantityText: String = "",
        val quantityMilli: Long = 0L,
        val categoryId: String? = null,
        val subcategoryId: String? = null,
    )

    internal companion object {
        const val VERSION: Int = 1

        private val json = Json {
            ignoreUnknownKeys = true
            // A draft is mostly nulls by nature. Writing them costs bytes on a
            // column rewritten every 300 ms while someone types.
            explicitNulls = false
            encodeDefaults = true
        }

        fun encode(payload: ReviewDraftPayload): String = json.encodeToString(payload)

        /** Null when the payload cannot be read. See the note above. */
        fun decode(raw: String?): ReviewDraftPayload? =
            raw?.let { runCatching { json.decodeFromString<ReviewDraftPayload>(it) }.getOrNull() }
    }
}

/**
 * The typing worth saving, or null if the user has changed nothing.
 *
 * **Null is what stops a row being written for a screen nobody edited.** The
 * review screen loads from the parser's extraction, so "the state differs from
 * what was loaded" is the only honest definition of dirty — and without it,
 * merely opening a candidate would persist a draft, and every candidate in the
 * Inbox would look edited.
 */
internal fun ReviewUiState.toDraftPayload(): ReviewDraftPayload = ReviewDraftPayload(
    ledger = ledger?.name,
    amountText = amountText,
    occurredAt = occurredAt,
    noteText = noteText,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    itemised = itemised,
    lines = lines.map { line ->
        ReviewDraftPayload.Line(
            key = line.key,
            name = line.name,
            unitPriceText = line.unitPriceText,
            unitPriceMinor = line.unitPriceMinor,
            quantityText = line.quantityText,
            quantityMilli = line.quantityMilli,
            categoryId = line.categoryId,
            subcategoryId = line.subcategoryId,
        )
    },
)

/**
 * A saved draft, back over the state the extraction produced.
 *
 * Applied **after** [PendingTransaction.toUiState] rather than instead of it, so
 * everything the draft does not carry — the source label, the reference hint,
 * `needsManualFill`, the raw merchant name — still comes from the candidate.
 * Those are facts about the message, not things the user typed, and a draft has
 * no business overriding them.
 */
internal fun ReviewUiState.withDraft(payload: ReviewDraftPayload): ReviewUiState {
    val book = payload.ledger?.let { name ->
        LedgerType.entries.firstOrNull { it.name == name }
    }
    return copy(
        ledger = book ?: ledger,
        // The book control shows only while the parser could not read a
        // direction. A draft that supplied one does not make the message any
        // clearer, so the row stays -- otherwise the user could pick a book,
        // leave, come back, and find the control gone.
        bookIsUnread = bookIsUnread,
        amountText = payload.amountText,
        occurredAt = payload.occurredAt ?: occurredAt,
        noteText = payload.noteText,
        categoryId = payload.categoryId,
        subcategoryId = payload.subcategoryId,
        merchantId = payload.merchantId,
        paymentMethodId = payload.paymentMethodId,
        itemised = payload.itemised,
        lines = payload.lines.map { line ->
            ReviewLine(
                key = line.key,
                name = line.name,
                unitPriceText = line.unitPriceText,
                unitPriceMinor = line.unitPriceMinor,
                quantityText = line.quantityText,
                quantityMilli = line.quantityMilli,
                categoryId = line.categoryId,
                subcategoryId = line.subcategoryId,
            )
        },
    )
}
