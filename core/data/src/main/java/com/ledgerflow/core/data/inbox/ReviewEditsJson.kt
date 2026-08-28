package com.ledgerflow.core.data.inbox

import com.ledgerflow.core.domain.inbox.ReviewEditLine
import com.ledgerflow.core.domain.inbox.ReviewEdits
import com.ledgerflow.core.model.LedgerType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `pending_transaction.review_draft_json`, encoded and decoded. v8.
 *
 * **Here rather than in `:feature:inbox`, beside `ExtractedTransactionJson`.**
 * It shipped in the feature on the reasoning that form state belongs to the
 * screen that produces it — SPEC.md §6.1.2's split for
 * `draft_entry.payload_json`. That was wrong for a *candidate*, and the symptom
 * was exact: the Inbox and Ledger rows kept showing the parser's amount after
 * the user had corrected it, because nothing but the review screen could read
 * the correction. A draft's payload really is one screen's business; a
 * candidate's is not, because a candidate is a row other surfaces list.
 *
 * **The wire format is unchanged by that move.** Every field name below is the
 * one v8 already wrote, so a payload sitting on a device from before this
 * decodes exactly as it did. Renaming one would silently drop whatever the user
 * had typed — the row would open from the extraction and look like it had never
 * been edited, which is the failure this whole change exists to fix.
 *
 * ## Versioning
 *
 * [version] rides inside the envelope rather than in a column, exactly as
 * `extracted_json` does — a `payload_version` column would be a schema bump to
 * carry an integer that fits in the JSON it describes. [json] ignores unknown
 * keys and every field defaults, so a payload written by a later build and one
 * read by this build both survive.
 *
 * **An unreadable payload is discarded, not repaired.** [decode] returns null
 * and the candidate reads as unedited — the same outcome as never having typed
 * anything, which is the honest one. Guessing at a half-understood payload
 * would put figures in front of the user that they did not enter.
 */
internal object ReviewEditsJson {

    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        // A draft is mostly nulls by nature. Writing them costs bytes on a
        // column rewritten every 300 ms while someone types.
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(edits: ReviewEdits): String = json.encodeToString(edits.toPayload())

    /** Null when the payload cannot be read. See the note above. */
    fun decode(raw: String?): ReviewEdits? =
        raw?.let {
            runCatching { json.decodeFromString<Payload>(it) }.getOrNull()?.toDomain()
        }

    @Serializable
    internal data class Payload(
        val version: Int = VERSION,
        val ledger: String? = null,
        val amountText: String = "",
        val amountMinor: Long? = null,
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
    }
}

private fun ReviewEdits.toPayload() = ReviewEditsJson.Payload(
    ledger = ledger?.name,
    amountText = amountText,
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    noteText = noteText,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    itemised = itemised,
    lines = lines.map { line ->
        ReviewEditsJson.Payload.Line(
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
 * An unrecognised `ledger` decodes to null rather than throwing.
 *
 * The enum could gain or lose a name across builds, and a draft is not worth
 * failing a whole candidate's read over — a null book means the review screen
 * asks, which is what it already does for a message with no direction.
 */
private fun ReviewEditsJson.Payload.toDomain() = ReviewEdits(
    ledger = ledger?.let { name -> LedgerType.entries.firstOrNull { it.name == name } },
    amountText = amountText,
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    noteText = noteText,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    itemised = itemised,
    lines = lines.map { line ->
        ReviewEditLine(
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
