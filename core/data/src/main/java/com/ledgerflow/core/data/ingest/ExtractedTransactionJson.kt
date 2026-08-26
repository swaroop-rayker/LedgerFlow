package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.model.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `pending_transaction.extracted_json`, the versioned typed payload §6.1 names.
 * P2-4.
 *
 * **The split is the point.** [ExtractedTransaction] lives in `:core:domain`
 * because `:feature:inbox` has to render one at P2-6 and features may not depend
 * on features (CLAUDE.md §3); the *encoding* lives here, in `:core:data`,
 * because a wire format is a storage concern and putting `@Serializable` on the
 * domain type would make every future field rename a data-migration question
 * disguised as a refactor.
 *
 * This is the deliberate difference from `draft_entry.payload_json`, which
 * `:core:data` carries across **unread** — that payload belongs to the entry
 * form, a feature, and deserializing it here would put the form's field names in
 * a layer that must not know them. A candidate's fields are §5.1's extraction
 * targets, which are a spec-level list, not one screen's shape.
 *
 * **The version lives inside the envelope, not in a column.** `draft_entry` has
 * a `payload_version`; `pending_transaction` does not, and adding one would be
 * schema v8 and a migration on a live device to carry a single integer that fits
 * in the JSON it describes. [VERSION] is written on every payload and read back
 * leniently — see [decode].
 */
internal object ExtractedTransactionJson {

    /**
     * v1: §5.1's nine extraction targets plus the confidence score.
     *
     * Bump this when a field changes *meaning*. Adding a nullable field does not
     * need a bump — [json] ignores unknown keys and the DTO defaults every field,
     * so a v1 payload read by a later build and a later payload read by this one
     * both survive. That symmetry is what stops an app update from orphaning a
     * user's unreviewed queue, which is `BackupPayload`'s lesson applied one
     * table over.
     */
    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        // The Inbox has to survive a payload written by a build that knew more
        // fields than this one does. Explicit nulls add bytes to a column that
        // is written once per captured message and read once per review.
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(extracted: ExtractedTransaction): String = json.encodeToString(
        Payload(
            version = VERSION,
            amountMinor = extracted.amount?.minor,
            currency = extracted.currency,
            direction = extracted.direction.name,
            merchantRaw = extracted.merchantRaw,
            accountLast4 = extracted.accountLast4,
            instrumentHint = extracted.instrumentHint.name,
            referenceNo = extracted.referenceNo,
            occurredAt = extracted.occurredAt,
            availableBalanceMinor = extracted.availableBalance?.minor,
            confidence = extracted.confidence,
        ),
    )

    /**
     * The payload as the Inbox will want it, or null if the column is not a
     * payload at all.
     *
     * Null rather than throwing, and an unrecognised enum name degrading to
     * `UNKNOWN` rather than failing the row: the alternative is that one
     * malformed candidate makes the whole Inbox unopenable, which is the same
     * shape of failure as one bad `parser_rule` stopping every other rule from
     * loading — and that one is already handled this way.
     */
    fun decode(payloadJson: String): ExtractedTransaction? = runCatching {
        val payload = json.decodeFromString<Payload>(payloadJson)
        ExtractedTransaction(
            amount = payload.amountMinor?.let(::Money),
            currency = payload.currency,
            direction = ExtractedDirection.entries
                .firstOrNull { it.name == payload.direction }
                ?: ExtractedDirection.UNKNOWN,
            merchantRaw = payload.merchantRaw,
            accountLast4 = payload.accountLast4,
            instrumentHint = InstrumentHint.entries
                .firstOrNull { it.name == payload.instrumentHint }
                ?: InstrumentHint.UNKNOWN,
            referenceNo = payload.referenceNo,
            occurredAt = payload.occurredAt,
            availableBalance = payload.availableBalanceMinor?.let(::Money),
            confidence = payload.confidence,
        )
    }.getOrNull()

    /**
     * Money crosses the wire as minor units, never as a decimal string.
     *
     * Law 3 is about arithmetic, but the serialised form is where a `Double`
     * would sneak back in: a payload holding `"amount": 788.0` reconstitutes as
     * a value that cannot represent every rupee amount exactly. The column holds
     * the same `Long` the ledger does.
     */
    @Serializable
    private data class Payload(
        @SerialName("v") val version: Int = VERSION,
        val amountMinor: Long? = null,
        val currency: String? = null,
        val direction: String = ExtractedDirection.UNKNOWN.name,
        val merchantRaw: String? = null,
        val accountLast4: String? = null,
        val instrumentHint: String = InstrumentHint.UNKNOWN.name,
        val referenceNo: String? = null,
        val occurredAt: Long? = null,
        val availableBalanceMinor: Long? = null,
        val confidence: Double = 0.0,
    )
}
