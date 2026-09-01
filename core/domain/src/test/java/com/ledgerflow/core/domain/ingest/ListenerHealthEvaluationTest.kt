package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * §5.2's "> 6 h" rule, pinned.
 *
 * Every input is a value, so the boundary can be tested exactly rather than
 * approached with a sleep. That is the reason [ListenerHealth.evaluate] takes a
 * clock reading and an uptime instead of reading either: a test that has to wait
 * six hours is a test nobody runs, and a test that waits one second to prove a
 * six-hour rule is proving something else.
 *
 * **The assertions are written against the boundary rather than around it.**
 * The kickoff's §2.5 lesson is that a green test can be asking the wrong
 * question, and the shape that hides here is an off-by-one that only ever fires
 * on an interval nobody will type by hand. `atExactlyTheThreshold` and
 * `oneMillisecondPast` are one millisecond apart and must disagree.
 */
class ListenerHealthEvaluationTest {

    private val now = 1_700_000_000_000L
    private val sixHours = ListenerHealth.DEAD_THRESHOLD_MILLIS
    private val longUptime = ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS * 10

    private fun evaluate(
        status: IngestSourceStatus = IngestSourceStatus.READY,
        record: ListenerHealthRecord,
        uptime: Long = longUptime,
    ) = ListenerHealth.evaluate(status, record, now, uptime)

    @Test
    fun evaluate_withoutTheGrant_reportsNotGranted() {
        val health = evaluate(
            status = IngestSourceStatus.PERMISSION_REQUIRED,
            // Stale by a week. The grant outranks it: telling a user their
            // listener died six hours ago, when what actually happened is that
            // they revoked access, sends them to the wrong screen.
            record = ListenerHealthRecord(lastConnectedAt = now - sixHours * 28),
        )

        assertThat(health).isEqualTo(NotificationCaptureHealth.NOT_GRANTED)
    }

    @Test
    fun evaluate_whileConnected_reportsConnectedHoweverOldTheTimestampIs() {
        // The steady state, and the one a timestamps-only design gets wrong.
        // A listener bound continuously for three days has a three-day-old
        // `lastConnectedAt` and is perfectly healthy; without the in-process
        // flag this would read as a three-day outage.
        val health = evaluate(
            record = ListenerHealthRecord(
                connected = true,
                lastConnectedAt = now - sixHours * 12,
            ),
        )

        assertThat(health).isEqualTo(NotificationCaptureHealth.CONNECTED)
    }

    @Test
    fun evaluate_atExactlyTheThreshold_isNotYetDead() {
        val health = evaluate(record = ListenerHealthRecord(lastConnectedAt = now - sixHours))

        assertThat(health).isEqualTo(NotificationCaptureHealth.RECONNECTING)
    }

    @Test
    fun evaluate_oneMillisecondPastTheThreshold_isDead() {
        val health = evaluate(record = ListenerHealthRecord(lastConnectedAt = now - sixHours - 1))

        assertThat(health).isEqualTo(NotificationCaptureHealth.DEAD)
    }

    /**
     * The cold-start case, and the reason [ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS]
     * exists.
     *
     * The Dashboard is the shell's start destination, so it renders in the first
     * frames of every launch — at which point the listener has legitimately not
     * been re-bound yet and `connected` is `false`. Without the uptime guard,
     * any install whose last connect is older than six hours (a phone that was
     * off overnight) would flash the banner on every single cold start.
     */
    @Test
    fun evaluate_beforeTheProcessHasBeenUpLong_staysQuietEvenWhenStale() {
        val health = evaluate(
            record = ListenerHealthRecord(lastConnectedAt = now - sixHours * 4),
            uptime = ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS - 1,
        )

        assertThat(health).isEqualTo(NotificationCaptureHealth.RECONNECTING)
    }

    /**
     * A clean unbind followed by silence.
     *
     * `lastDisconnectedAt` is ahead of `lastConnectedAt` here, so it is the
     * reference — which matters because using the older of the two would report
     * an outage longer than the one that happened.
     */
    @Test
    fun evaluate_afterACleanDisconnect_measuresFromTheDisconnect() {
        val record = ListenerHealthRecord(
            lastConnectedAt = now - sixHours * 3,
            lastDisconnectedAt = now - sixHours - 1,
        )

        assertThat(evaluate(record = record)).isEqualTo(NotificationCaptureHealth.DEAD)
        assertThat(record.lastKnownAliveAt).isEqualTo(now - sixHours - 1)
    }

    /**
     * A silent process kill: the connect is recorded, the disconnect never got
     * to write, and a *stale* disconnect from an earlier cycle is still on disk.
     *
     * Preferring `lastDisconnectedAt` unconditionally would measure from that
     * older value and over-report the outage. The `max` is what makes one rule
     * cover both orderings.
     */
    @Test
    fun evaluate_afterASilentKill_measuresFromTheConnect() {
        val record = ListenerHealthRecord(
            lastConnectedAt = now - 1000,
            lastDisconnectedAt = now - sixHours * 9,
        )

        assertThat(evaluate(record = record)).isEqualTo(NotificationCaptureHealth.RECONNECTING)
        assertThat(record.lastKnownAliveAt).isEqualTo(now - 1000)
    }

    /**
     * A grant that is honoured in Settings and never actually bound.
     *
     * Without `grantObservedAt` there is no start point, the record reads as
     * "nothing known", and the banner would stay silent forever on an install
     * that has never captured anything — the worst possible case to be quiet
     * about.
     */
    @Test
    fun evaluate_withAGrantThatNeverBound_goesDeadFromTheGrantObservation() {
        val health = evaluate(
            record = ListenerHealthRecord(grantObservedAt = now - sixHours - 1),
        )

        assertThat(health).isEqualTo(NotificationCaptureHealth.DEAD)
    }

    @Test
    fun evaluate_withNothingKnownAtAll_staysQuiet() {
        // The one-poll window between the grant being seen and `grantObservedAt`
        // being stamped. Saying nothing is right: the next poll has a reference.
        assertThat(evaluate(record = ListenerHealthRecord()))
            .isEqualTo(NotificationCaptureHealth.RECONNECTING)
    }

    /**
     * A backwards wall clock reads as healthy, not as an outage.
     *
     * `nowMillis` has to be wall clock — the interval spans reboots — so an NTP
     * correction or a user changing the date can make the subtraction negative.
     * The quiet answer is the right direction for a signal whose only failure
     * mode that matters is crying wolf.
     */
    @Test
    fun evaluate_whenTheClockHasMovedBackwards_staysQuiet() {
        val health = evaluate(record = ListenerHealthRecord(lastConnectedAt = now + sixHours * 4))

        assertThat(health).isEqualTo(NotificationCaptureHealth.RECONNECTING)
    }

    /**
     * The two SMS-only statuses render nothing rather than a banner.
     *
     * Unreachable for the notification source today, and asserted anyway: the
     * `when` in [ListenerHealth.evaluate] has no `else`, so if a future status
     * is added these are the branches that will need an answer, and a test that
     * skipped them would let the compiler's exhaustiveness check be satisfied by
     * whatever was easiest to type.
     */
    @Test
    fun evaluate_forASourceThatCannotRun_reportsUnavailable() {
        val record = ListenerHealthRecord(lastConnectedAt = now - sixHours * 4)

        assertThat(evaluate(IngestSourceStatus.UNSUPPORTED_IN_BUILD, record))
            .isEqualTo(NotificationCaptureHealth.UNAVAILABLE)
        assertThat(evaluate(IngestSourceStatus.UNAVAILABLE_ON_DEVICE, record))
            .isEqualTo(NotificationCaptureHealth.UNAVAILABLE)
    }

    /** §5.2 says six hours. If this fails, the spec changed or the constant did. */
    @Test
    fun deadThreshold_isSixHours() {
        assertThat(ListenerHealth.DEAD_THRESHOLD_MILLIS).isEqualTo(6L * 60L * 60L * 1000L)
    }
}
