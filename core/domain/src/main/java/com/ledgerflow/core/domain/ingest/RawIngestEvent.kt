package com.ledgerflow.core.domain.ingest

/**
 * Which capture adapter produced an event (SPEC.md §3.1).
 *
 * This exists so the *pipeline* never has to ask. It is carried on
 * [RawIngestEvent] because P2 persists it — `sms_raw` and `notification_raw` are
 * separate tables and the Inbox's "Suppressed" filter has to be able to say
 * which of two rows survived a cross-source dedupe — **not** so downstream code
 * can branch on it. CLAUDE.md §0: everything downstream of a capture adapter is
 * source-agnostic, and an `if (source == SMS)` outside an adapter package is the
 * abstraction already broken.
 */
public enum class IngestSourceType {
    /** A bank/UPI SMS, captured by a `BroadcastReceiver`. `smsFull` only. */
    SMS,

    /** A bank/UPI/card app notification, captured by a listener service. Both flavours. */
    NOTIFICATION,
}

/**
 * One captured message, normalized, before anything has looked at it.
 *
 * The exact shape SPEC.md §3.1 names, and the whole point of the D-04 split: an
 * SMS and a GPay notification arrive through completely different platform APIs
 * and become the same type here. Everything past this point — allowlist, rule
 * engine, dedupe, `pending_transaction`, the Inbox — sees only this.
 *
 * It is a dumb carrier on purpose. No parsing, no confidence, no extracted
 * amount: a capture adapter has ~10 seconds before the system kills it
 * (CLAUDE.md §7), so its entire job is to fill this in and hand it on. The
 * fields are also exactly what P2 writes verbatim into `sms_raw` /
 * `notification_raw`, which is what makes an unparseable message replayable
 * against a later ruleset instead of lost.
 *
 * @param sourceType which adapter produced this. Persisted, never branched on.
 * @param sender the originating address for SMS (`VM-HDFCBK`), or the posting
 *   app's **user-visible label** for a notification ("Google Pay"), resolved
 *   once from `PackageManager` — **D-11**. Not the package name repeated, which
 *   carries nothing [packageName] does not already hold, and not the
 *   notification's title, which is per-notification content and a poor input to
 *   a dedupe key that has to be stable across two sources. The rule engine
 *   matches on this for SMS and on [packageName] for notifications — the one
 *   genuinely source-specific field, and §5.2 says so explicitly.
 * @param body the message text. For a notification this is the flattened
 *   title + text + bigText + subText (§5.2), already joined by the adapter, so
 *   that a single regex ruleset can run against either source.
 * @param receivedAt epoch millis, from the injected [com.ledgerflow.core.common.time.Clock],
 *   meaning *when this device captured it* — not when the bank sent it. The
 *   transaction's own time is an extraction target (§5.1) and lands on
 *   `pending_transaction` at P2; the two are different values and conflating
 *   them would put a delayed SMS in the wrong day.
 * @param title the notification's own title, `null` for SMS. Captured
 *   separately rather than folded into [sender] (D-11) — it is sometimes the
 *   bank, sometimes the merchant, sometimes an amount — and lands in
 *   `notification_raw.title`, where the schema always had a home for it. It is
 *   also already included in [body], because §5.2 flattens title + text +
 *   bigText + subText so one ruleset can run against either source; this field
 *   is for showing the user where a pending row came from, not for parsing.
 * @param packageName the posting package for a notification
 *   (`com.google.android.apps.nbu.paisa.user`), `null` for SMS, which has no
 *   package. Nullable rather than blank so "no such concept" and "empty string"
 *   stay distinguishable in the raw tables.
 */
public data class RawIngestEvent(
    val sourceType: IngestSourceType,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val packageName: String? = null,
    val title: String? = null,
)
