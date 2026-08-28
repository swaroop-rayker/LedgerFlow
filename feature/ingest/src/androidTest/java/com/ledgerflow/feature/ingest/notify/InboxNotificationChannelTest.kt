package com.ledgerflow.feature.ingest.notify

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §5.1's `inbox_high` channel, on a real device. P2-7.
 *
 * **Instrumented rather than a JVM test, because a `NotificationChannel` off a
 * device is a stub that records whatever you set and agrees with you.** What is
 * actually under test is the platform's behaviour: that the channel lands with
 * the importance §5.1 asks for, that it is silent by default, and — the part
 * that has bitten every Android codebase at least once — that creating it again
 * is harmless. None of those are properties of our Kotlin.
 *
 * The channel is deleted in setup and teardown so the assertions describe a
 * fresh install. That matters more here than in most tests: **a channel's
 * importance and sound cannot be changed after creation**, so a run against a
 * channel left behind by the app under test would assert whatever that install
 * happened to have, including anything the owner had changed by hand.
 */
@RunWith(AndroidJUnit4::class)
class InboxNotificationChannelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() = resetChannel()

    @After
    fun tearDown() = resetChannel()

    /**
     * Delete the channel *and* the once-per-process latch.
     *
     * `ensureChannel` short-circuits after its first call, so a second test in
     * the same instrumentation process would otherwise find no channel and
     * create none — passing or failing on the order the tests happened to run
     * in. The latch is private, so it is cleared reflectively rather than by
     * widening the production API for a test's convenience.
     */
    private fun resetChannel() {
        manager.deleteNotificationChannel(CHANNEL_ID)
        val field = InboxNotifications::class.java.getDeclaredField("channelReady")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(InboxNotifications) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    @Test
    fun ensureChannel_createsTheChannelSpecInFiveOne() {
        InboxNotifications.ensureChannel(context)

        val channel = requireNotNull(manager.getNotificationChannel(CHANNEL_ID)) {
            "§5.1 names the channel `$CHANNEL_ID`; nothing created it."
        }
        // "Importance HIGH" -- the candidate is worth a heads-up.
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        // "...no sound by default" -- the other half of the same sentence, and
        // the half that keeps the user from muting the app wholesale.
        assertThat(channel.sound).isNull()
        assertThat(channel.shouldVibrate()).isFalse()
    }

    /**
     * The DoD's first bullet: created once, idempotently.
     *
     * Calling twice must leave one channel with unchanged settings rather than
     * throwing or resetting anything. `LedgerFlowApplication.onCreate` runs on
     * every process entry — including a receiver-only wake with no Activity —
     * so the repeat is the normal case, not the edge case.
     */
    @Test
    fun ensureChannel_calledRepeatedly_leavesOneUnchangedChannel() {
        InboxNotifications.ensureChannel(context)
        val first = requireNotNull(manager.getNotificationChannel(CHANNEL_ID))

        InboxNotifications.ensureChannel(context)
        InboxNotifications.ensureChannel(context)

        val after = requireNotNull(manager.getNotificationChannel(CHANNEL_ID))
        assertThat(after.importance).isEqualTo(first.importance)
        assertThat(after.sound).isEqualTo(first.sound)
        assertThat(manager.notificationChannels.count { it.id == CHANNEL_ID }).isEqualTo(1)
    }

    /**
     * The latch does what it claims: one binder round-trip per process.
     *
     * Asserted by deleting the channel *behind* `ensureChannel`'s back and
     * calling it again — if it were doing the work every time, the channel would
     * come back. It does not, which is the whole point of "not on every post" on
     * a path that also has a `BroadcastReceiver`'s ten seconds to respect.
     *
     * This is the one place that behaviour is observable, and it is worth
     * pinning: making `ensureChannel` unconditional would be an easy and
     * completely invisible regression.
     */
    @Test
    fun ensureChannel_afterTheFirstCall_doesNoFurtherWork() {
        InboxNotifications.ensureChannel(context)
        assertThat(manager.getNotificationChannel(CHANNEL_ID)).isNotNull()

        manager.deleteNotificationChannel(CHANNEL_ID)
        InboxNotifications.ensureChannel(context)

        assertThat(manager.getNotificationChannel(CHANNEL_ID)).isNull()
    }

    private companion object {
        /** §5.1 names this literally. Duplicated rather than imported: if the
         *  constant is ever renamed, this test should fail rather than follow. */
        const val CHANNEL_ID = "inbox_high"
    }
}
