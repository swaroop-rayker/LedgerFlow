package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus

/**
 * A captured SMS, verbatim (SPEC.md §5.1, §6.1). Schema v6.
 *
 * **Written before anything has looked at it.** The receiver has ~10 seconds
 * before the system kills it (CLAUDE.md §7), so its whole job is to land this
 * row and enqueue a worker. Parsing, the sender allowlist and dedupe all happen
 * later, off this row.
 *
 * Nothing here is a ledger entry and nothing here counts towards a total. Law 1
 * is unaffected: this table feeds `pending_transaction`, which feeds a human.
 */
@Entity(
    tableName = "sms_raw",
    indices = [
        // §5.1's dedupe-on-capture: the same message can be delivered twice by
        // the network. Unique so the second insert is refused by the database
        // rather than by a check the caller might forget.
        Index(value = ["body_hash"], unique = true),
        // The worker sweeps what it has not handled yet.
        Index(value = ["parse_status", "received_at"]),
        // D-09's retention purge scans on this alone.
        Index(value = ["retention_expires_at"]),
    ],
)
public data class SmsRawEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** The originating address as the network gave it, e.g. `VM-HDFCBK`. */
    @ColumnInfo(name = "sender")
    val sender: String,

    /**
     * The message text, reassembled across multipart parts.
     *
     * **Cleared by the D-09 retention purge, which keeps the row.** A body of
     * `""` past `retention_expires_at` means "purged", not "empty message" — no
     * SMS is ever captured with an empty body.
     */
    @ColumnInfo(name = "body")
    val body: String,

    /**
     * SHA-256 over sender + normalized body + minute bucket (§5.1).
     *
     * Computed at capture and never recomputed, which is what keeps it usable
     * after the body has been purged.
     */
    @ColumnInfo(name = "body_hash")
    val bodyHash: String,

    /**
     * When *this device* captured the message — not when the bank sent it.
     *
     * The transaction's own time is an extraction target and lands on
     * `pending_transaction`. Conflating the two puts a delayed SMS in the wrong
     * day.
     */
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    /** Which SIM took it, where the platform reports one. */
    @ColumnInfo(name = "sim_slot")
    val simSlot: Int?,

    @ColumnInfo(name = "parse_status")
    val parseStatus: RawParseStatus,

    /** Which `parser_rule` matched, for the rule test bench and for debugging. */
    @ColumnInfo(name = "matched_rule_id")
    val matchedRuleId: String?,

    /** `received_at` + 90 days (D-09). The purge clears [body] at this point. */
    @ColumnInfo(name = "retention_expires_at")
    val retentionExpiresAt: Long,
)

/**
 * A captured notification from an allowlisted package (SPEC.md §5.2, §6.1).
 * Schema v6.
 *
 * **A row here means the package was on the allowlist.** §5.2's privacy rule is
 * that the filter runs before any body access, so a non-allowlisted package
 * never reaches this table — there is no "rejected" state to record, because
 * nothing was read to record. That is the asymmetry with [SmsRawEntity], whose
 * sender allowlist is applied in the worker after the row exists.
 */
@Entity(
    tableName = "notification_raw",
    indices = [
        Index(value = ["body_hash"], unique = true),
        Index(value = ["parse_status", "posted_at"]),
        Index(value = ["retention_expires_at"]),
        // The Settings allowlist screen shows what a package has been posting.
        Index(value = ["package_name", "posted_at"]),
    ],
)
public data class NotificationRawEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** e.g. `com.google.android.apps.nbu.paisa.user`. The rule engine's match field. */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /**
     * The notification's own title.
     *
     * Kept separate from [body] and **not** used as the event's `sender`
     * (D-11): it is per-notification content — sometimes the bank, sometimes
     * the merchant, sometimes an amount — which makes it a poor input to a
     * dedupe key that has to be stable across two sources.
     */
    @ColumnInfo(name = "title")
    val title: String?,

    /**
     * Title + text + bigText + subText, flattened by the adapter (§5.2).
     *
     * Flattened at capture so one regex ruleset runs against either source.
     * Cleared by the D-09 purge, same as [SmsRawEntity.body].
     */
    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "body_hash")
    val bodyHash: String,

    @ColumnInfo(name = "posted_at")
    val postedAt: Long,

    @ColumnInfo(name = "parse_status")
    val parseStatus: RawParseStatus,

    @ColumnInfo(name = "matched_rule_id")
    val matchedRuleId: String?,

    @ColumnInfo(name = "retention_expires_at")
    val retentionExpiresAt: Long,
)

/**
 * Which packages LedgerFlow may read notifications from (D-10). Schema v6.
 *
 * **This table is the privacy guarantee, in the one place that enforces it.**
 * §5.2 says content from a non-allowlisted package is never read, logged or
 * persisted, and the only way that holds is if the check happens before the
 * body is touched. Seeded with a curated default (GPay/PhonePe/Paytm/major
 * Indian banks) and fully user-editable — D-10 rejected both an empty default
 * (the higher-recall source capturing nothing on first run) and a locked list
 * (a guarantee the user cannot narrow).
 *
 * [enabled] rather than deletion, so turning a package off and on again does
 * not lose its label, and so the Settings screen can show the curated set
 * greyed rather than vanished.
 */
@Entity(tableName = "package_allowlist")
public data class PackageAllowlistEntity(

    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** The app's user-visible name, resolved from `PackageManager` (D-11). */
    @ColumnInfo(name = "label")
    val label: String?,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
)

/**
 * Which SMS senders count as financial (SPEC.md §5.1, §6.1). Schema v6.
 *
 * Applied in the worker, not the receiver — see [SmsRawEntity]. A pattern
 * rather than an exact address because Indian sender IDs carry a rotating
 * operator prefix (`VM-HDFCBK`, `AD-HDFCBK`, `JD-HDFCBK` are one bank).
 */
@Entity(tableName = "sender_allowlist")
public data class SenderAllowlistEntity(

    @PrimaryKey
    @ColumnInfo(name = "sender_pattern")
    val senderPattern: String,

    @ColumnInfo(name = "label")
    val label: String?,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
)

/**
 * One versioned extraction rule (SPEC.md §5.1, §6.1). Schema v6.
 *
 * Rules ship in `assets/parser_rules/v{N}.json` and are loaded into this table
 * on first run and on version bump. They live in a table rather than only in
 * the asset because §5.1 gives the user a rule editor: a user-defined rule
 * ([isUserDefined]) has nowhere else to live, and a shipped rule the user
 * disabled has to survive the next ruleset load.
 *
 * **Shared across both sources.** [senderPattern] matches `sms_raw.sender` for
 * SMS and `notification_raw.package_name` for notifications — §5.2 is explicit
 * that this is the *only* source-specific thing about the engine.
 */
@Entity(
    tableName = "parser_rule",
    indices = [
        // The engine walks enabled rules of the current ruleset in priority
        // order, which is exactly this index.
        Index(value = ["ruleset_version", "enabled", "priority"]),
    ],
)
public data class ParserRuleEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "ruleset_version")
    val rulesetVersion: Int,

    /** Lower runs first. Ties are broken by [id] so matching is deterministic. */
    @ColumnInfo(name = "priority")
    val priority: Int,

    @ColumnInfo(name = "sender_pattern")
    val senderPattern: String,

    /** Regex with named groups; [fieldMapJson] says which group feeds which field. */
    @ColumnInfo(name = "body_pattern")
    val bodyPattern: String,

    @ColumnInfo(name = "field_map_json")
    val fieldMapJson: String,

    /**
     * `DEBIT` / `CREDIT` when the rule itself decides the book, null when the
     * body does.
     *
     * A `String?` rather than `LedgerType?` on purpose: this is rule *data*
     * loaded from an asset that a user can edit, and a value the enum does not
     * know has to be storable and then rejected by the engine with a message,
     * rather than silently decoding to null at the Room boundary.
     */
    @ColumnInfo(name = "direction")
    val direction: String?,

    /**
     * `UPI` / `CARD` / ... when the rule itself knows how the money moved.
     *
     * Added in v7. A rule that only matches GPay, PhonePe, Paytm and BHIM is
     * describing a UPI payment whether or not the notification says the word —
     * and most do not. Carried on the rule rather than inferred from the
     * package downstream, because inferring it there would be the pipeline
     * branching on source, which CLAUDE.md §0 forbids outside an adapter.
     *
     * A `String?` for the same reason [direction] is: rule data comes from an
     * asset a user can edit, and an unrecognised value must be storable and then
     * rejected with a message rather than decoding to null at the Room boundary.
     */
    @ColumnInfo(name = "instrument_hint")
    val instrumentHint: String?,

    /** Starting confidence before per-field adjustments. Not money — a real is correct here (Law 3). */
    @ColumnInfo(name = "confidence_base")
    val confidenceBase: Double,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,

    /** True for a rule the user wrote. Never overwritten by a ruleset load. */
    @ColumnInfo(name = "is_user_defined")
    val isUserDefined: Boolean,
)

/**
 * One candidate transaction awaiting a human (SPEC.md §5.1, §6.1). Schema v6.
 *
 * **This table is Law 1.** Parsers, workers and receivers write here and
 * nowhere else; only `ApproveTransactionUseCase` reads a row and inserts into
 * `ledger_entry`. Nothing in this table appears in any total, any ledger query
 * or any rollup.
 *
 * Manual entry deliberately does *not* route through here (§5.4): the Save tap
 * on a form the user just filled in already **is** the human act Law 1 exists
 * to require, so it calls the use case directly with `source = MANUAL`. The
 * `MANUAL` value on [source] stays reachable for an ingest path that needs to
 * park a hand-built candidate, not because the entry form uses it.
 */
@Entity(
    tableName = "pending_transaction",
    indices = [
        // §6.1's INDEX(status, created_at DESC). SQLite scans an index
        // backwards as cheaply as forwards, so this serves the Inbox's
        // newest-first ordering without a second index.
        Index(value = ["status", "created_at"]),
        // Dedupe looks up recent rows by key inside a ±3 minute window (§3.1).
        Index(value = ["dedupe_key", "created_at"]),
        // The Inbox's "Suppressed" filter, and the walk back to the winner.
        Index(value = ["suppressed_by_id"]),
    ],
)
public data class PendingTransactionEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Which capture produced this. Persisted for the audit trail, never branched on (CLAUDE.md §0). */
    @ColumnInfo(name = "source")
    val source: EntrySource,

    /** §3.1's cross-source key: amount, direction, minute, and account or merchant. */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    /**
     * The row that won a cross-source dedupe, when this one lost.
     *
     * Non-null is what the Inbox's "Suppressed" filter reads. **No foreign key,
     * deliberately**: the winner can be discarded and later purged, and a
     * cascade would then delete the evidence that a duplicate was ever
     * suppressed. A dangling id here reads as "suppressed, winner gone", which
     * is true and is better than the row disappearing.
     */
    @ColumnInfo(name = "suppressed_by_id")
    val suppressedById: String?,

    /** `sms_raw.id`, `notification_raw.id`, or (at P4) `attachment.id`. */
    @ColumnInfo(name = "raw_ref_id")
    val rawRefId: String?,

    /**
     * The extracted fields, as a versioned typed payload.
     *
     * JSON for the same reason `draft_entry.payload_json` is: a candidate is
     * partial by definition — an amount with no merchant, a merchant with no
     * date — so every typed column would be nullable anyway, and the set of
     * extraction targets grows with the ruleset rather than with the schema.
     */
    @ColumnInfo(name = "extracted_json")
    val extractedJson: String,

    /** 0.0 when nothing matched (§5.1). A score, not money — Law 3 does not apply. */
    @ColumnInfo(name = "confidence")
    val confidence: Double,

    @ColumnInfo(name = "status")
    val status: PendingStatus,

    /** Set with `confidence = 0`: the review screen opens with fields to fill rather than to check. */
    @ColumnInfo(name = "needs_manual_fill")
    val needsManualFill: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** When the user approved or discarded it. Null while [status] is `PENDING`. */
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: Long?,

    /**
     * The `ledger_entry` this became.
     *
     * No foreign key: `ledger_entry` rows are erasable (`PurgeDeletedEntries`),
     * and a cascade from there into the approval audit trail would delete the
     * record that the user ever approved anything.
     */
    @ColumnInfo(name = "approved_entry_id")
    val approvedEntryId: String?,

    /**
     * What the user has typed on the review screen but not yet approved. v8.
     *
     * **BUG6, applied to the Inbox.** The entry form persists to `draft_entry`
     * on every keystroke; review held its typing in a ViewModel, so a back
     * press — which pops the destination and destroys the ViewModel — threw it
     * away, and so did a process death. `SavedStateHandle` cannot help: it
     * survives a configuration change, not a destination leaving the back
     * stack.
     *
     * **A column here rather than a row in `draft_entry`**, which is the
     * shortcut §5.4 exists to refuse: routing a candidate through the drafts
     * stack would put a half-reviewed message where discarding it in one place
     * leaves it alive in the other. The candidate is already the row; this is
     * one more thing known about it.
     *
     * JSON for `draft_entry.payload_json`'s reasons: the state is partial and
     * invalid by definition — an amount mid-keystroke, no category chosen yet —
     * so typed columns would all be nullable, and it is never queried by any
     * dimension. It is written on a 300 ms debounce, so a single-row upsert is
     * also what keeps the screen off StrictMode's tripwire (§11).
     *
     * Null means "nothing typed yet"; the row then opens from the parser's
     * extraction exactly as before. **Cleared on approve and on discard**, so a
     * resolved candidate never carries stale typing.
     */
    @ColumnInfo(name = "review_draft_json")
    val reviewDraftJson: String? = null,
)
