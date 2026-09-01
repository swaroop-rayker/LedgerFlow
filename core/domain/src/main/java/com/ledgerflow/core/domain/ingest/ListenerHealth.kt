package com.ledgerflow.core.domain.ingest

/**
 * Everything known about the notification listener's liveness (SPEC.md §5.2).
 *
 * Three of the four fields are persisted, outside the vault, by
 * [ListenerHealthStore] — ADR-0020 has the reasoning. [connected] is
 * deliberately **not**, and that asymmetry is the whole design:
 *
 * - A persisted "connected" flag would be *wrong after a process kill*, which is
 *   the exact event the banner exists to report. The OEM battery manager that
 *   §5.2 names does not send a callback on its way out, so the last thing
 *   written would say "connected" forever and the banner would never appear.
 * - An in-process flag resets to `false` when the process starts, which is the
 *   truth: a listener in a dead process is not listening.
 *
 * So liveness is answered by the process, and *duration* is answered by the
 * disk. Neither alone can say "dead for more than six hours".
 */
public data class ListenerHealthRecord(

    /**
     * Whether *this* process currently holds a bound listener.
     *
     * Set by `onListenerConnected`, cleared by `onListenerDisconnected`, and
     * `false` for the first moments of every process — see
     * [ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS] for why that does not make
     * every cold start flash a banner.
     */
    val connected: Boolean = false,

    /** Last `onListenerConnected`. Null until the listener has ever bound. */
    val lastConnectedAt: Long? = null,

    /**
     * Last `onListenerDisconnected`.
     *
     * **Best-effort and allowed to be missing.** A disconnect caused by the
     * process being killed may not get its write to disk, which is precisely
     * why nothing here depends on this field being present.
     */
    val lastDisconnectedAt: Long? = null,

    /**
     * The first moment the app observed the grant held.
     *
     * The reference of last resort. Without it, a grant that is held while the
     * listener never once binds — an OEM that honours the setting and not the
     * bind — has no start point to measure from, and would report healthy
     * forever because there is no timestamp to call stale.
     */
    val grantObservedAt: Long? = null,
) {

    /**
     * The most recent moment there is any evidence the listener existed.
     *
     * A `max` rather than a preference between the two timestamps, because
     * which one is newer *is* the information. A clean unbind leaves
     * [lastDisconnectedAt] ahead; a silent process kill leaves
     * [lastConnectedAt] ahead with a stale disconnect from some earlier cycle
     * behind it. Taking the later of the two is right in both cases and needs
     * no branch on which happened.
     */
    public val lastKnownAliveAt: Long?
        get() = listOfNotNull(lastConnectedAt, lastDisconnectedAt, grantObservedAt).maxOrNull()
}

/**
 * What a screen says about notification capture (SPEC.md §5.2).
 *
 * Four states rather than a boolean, because the two unhealthy ones need
 * different sentences and different buttons: one is a grant the user has not
 * given, the other is a grant they gave that the system has since stopped
 * honouring. Telling someone to grant a permission they already granted is how
 * a health banner loses its reader.
 */
public enum class NotificationCaptureHealth {

    /**
     * Notification access has not been granted, or has been revoked.
     *
     * Actionable, and the only state that leads to the explainer: the grant
     * lives in system Settings and cannot be given in-app (§5.2).
     */
    NOT_GRANTED,

    /** Granted, bound, listening. Nothing to say. */
    CONNECTED,

    /**
     * Granted but not currently bound, and not for long enough to worry.
     *
     * The ordinary state during the first seconds of a cold start, and after any
     * transient unbind that `onListenerDisconnected` → `requestRebind()` is
     * already handling. **Deliberately silent** — a banner that appears every
     * time the app opens is one the user stops reading, and §5.2's six-hour
     * threshold exists to buy exactly this quiet.
     */
    RECONNECTING,

    /**
     * Granted, not bound, and no evidence of life for more than six hours.
     *
     * The state §5.2's banner exists for. `CLAUDE.md` §7: a dead listener must
     * not look like an empty Inbox.
     */
    DEAD,

    /**
     * The source cannot run in this build or on this device.
     *
     * Unreachable for notifications — every device that runs this app can host
     * a listener, and both flavours ship one — but [IngestSourceStatus] carries
     * the two SMS-only values and an exhaustive `when` may not have an `else`
     * on an enum (`CLAUDE.md` §5). Rendering nothing is the correct handling.
     */
    UNAVAILABLE,
}

/** The §5.2 rule, as a pure function. */
public object ListenerHealth {

    /** §5.2, verbatim: "if the service has been dead > 6 h". */
    public const val DEAD_THRESHOLD_MILLIS: Long = 6L * 60L * 60L * 1000L

    /**
     * How long the process must have been up before [NotificationCaptureHealth.DEAD]
     * is allowed as an answer.
     *
     * The Dashboard is the shell's start destination, so it renders in the first
     * frames of a cold start — and at that instant [ListenerHealthRecord.connected]
     * is legitimately `false` for every launch, because the system has not yet
     * re-bound the listener it is about to re-bind. Without this the banner
     * would flash on every single cold start and then vanish, which trains the
     * user to ignore it.
     *
     * Fifteen seconds is far longer than a bind takes and far shorter than six
     * hours, so it changes nothing about what the threshold means — it only
     * refuses to answer before the evidence exists.
     */
    public const val MIN_UPTIME_BEFORE_DEAD_MILLIS: Long = 15_000L

    /**
     * The banner's state, from the grant, the record and the clock.
     *
     * Pure and off-device by construction: every input is a value, so
     * `ListenerHealthEvaluationTest` can pin the six-hour boundary without a
     * device, a `NotificationManager` or a sleep.
     *
     * **A backwards clock reads as healthy, not dead.** `nowMillis` is wall
     * clock — it has to be, since the interval spans reboots and
     * `elapsedRealtime` does not — so an NTP correction or a user changing the
     * date can make the subtraction negative. Negative is not `>` the
     * threshold, so the quiet answer wins, which is the right direction for a
     * signal whose only failure mode that matters is crying wolf.
     */
    public fun evaluate(
        status: IngestSourceStatus,
        record: ListenerHealthRecord,
        nowMillis: Long,
        processUptimeMillis: Long,
    ): NotificationCaptureHealth = when (status) {
        IngestSourceStatus.UNSUPPORTED_IN_BUILD,
        IngestSourceStatus.UNAVAILABLE_ON_DEVICE,
        -> NotificationCaptureHealth.UNAVAILABLE

        IngestSourceStatus.PERMISSION_REQUIRED -> NotificationCaptureHealth.NOT_GRANTED

        IngestSourceStatus.READY -> when {
            record.connected -> NotificationCaptureHealth.CONNECTED
            processUptimeMillis < MIN_UPTIME_BEFORE_DEAD_MILLIS ->
                NotificationCaptureHealth.RECONNECTING

            else -> {
                val since = record.lastKnownAliveAt
                if (since != null && nowMillis - since > DEAD_THRESHOLD_MILLIS) {
                    NotificationCaptureHealth.DEAD
                } else {
                    // Null means the grant is held and nothing has been observed
                    // yet -- a state the very next poll resolves, because the
                    // use case stamps `grantObservedAt` the first time it sees a
                    // held grant with no history behind it.
                    NotificationCaptureHealth.RECONNECTING
                }
            }
        }
    }
}
