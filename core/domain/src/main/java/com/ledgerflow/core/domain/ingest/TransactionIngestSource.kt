package com.ledgerflow.core.domain.ingest

/**
 * A capture source, as the rest of the app is allowed to know it (SPEC.md §3.1,
 * ADR-0007).
 *
 * Deliberately **not** a thing you call to fetch messages. Both real adapters
 * are driven *by the platform* — a `BroadcastReceiver` the system invokes, a
 * `NotificationListenerService` the system binds — so there is no `poll()` and
 * no `start()` that would be honest. Events travel the other way, from the
 * adapter into `:feature:ingest`'s sink as a [RawIngestEvent].
 *
 * What is left is the part callers genuinely need and cannot get without
 * branching on the source: **is this source going to produce anything, and if
 * not, why not.** That is what makes a Settings row, onboarding's permission
 * priming, and §5.2's dashboard health banner able to render every source from
 * one loop instead of one `if` per flavour.
 *
 * The set of implementations is flavour-scoped and Hilt supplies it as a
 * multibinding, so `playSafe` and `smsFull` differ by *which objects are in the
 * set*, never by a branch at the call site. `playSafe` still binds an SMS
 * source; it reports [IngestSourceStatus.UNSUPPORTED_IN_BUILD] forever.
 */
public interface TransactionIngestSource {

    /** Which source this is. For display and for the raw-table split, not for branching. */
    public val sourceType: IngestSourceType

    /**
     * Whether this source can currently capture anything.
     *
     * Suspending because the answer comes from `PackageManager` and the
     * notification-listener grant list — cheap, but binder calls, and CLAUDE.md
     * §8 treats anything on the main thread as suspicious. Re-read on demand
     * rather than cached: a user can revoke notification access from Settings
     * while the app is in the background, and an OEM battery killer can drop
     * the listener without telling anyone (§5.2).
     */
    public suspend fun status(): IngestSourceStatus
}

/**
 * Why a source is or is not producing events.
 *
 * Ordered from "nothing to be done" to "working", and each value maps to one
 * sentence a screen can show. Two of them look similar and are not:
 * [UNSUPPORTED_IN_BUILD] is permanent and is the whole of D-04 —
 * `playSafe` ships no `RECEIVE_SMS` and never will, so offering the user a
 * button would be a lie — while [PERMISSION_REQUIRED] is a prompt away.
 */
public enum class IngestSourceStatus {

    /**
     * This flavour does not ship the capture path at all (SPEC.md §3.1).
     *
     * `playSafe`'s SMS source, permanently. Not a failure and not actionable:
     * the permission is Play-restricted, which is the reason the flavour exists.
     */
    UNSUPPORTED_IN_BUILD,

    /**
     * The build has the path but the hardware does not — no telephony, so no SMS.
     *
     * Distinct from the above because the fix is different (there isn't one, but
     * it is the *device's* limitation) and because the same APK is expected to
     * run on a tablet.
     */
    UNAVAILABLE_ON_DEVICE,

    /**
     * The user has not granted the permission this source needs.
     *
     * The only actionable value: `RECEIVE_SMS` via the runtime prompt,
     * notification access via the Settings deep link §5.2 describes (it cannot
     * be granted in-app).
     */
    PERMISSION_REQUIRED,

    /** Granted, supported, listening. */
    READY,
}
