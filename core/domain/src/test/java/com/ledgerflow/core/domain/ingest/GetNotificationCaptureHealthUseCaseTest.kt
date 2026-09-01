package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The two halves of §5.2's health answer, joined.
 *
 * [ListenerHealthEvaluationTest] pins the rule; this pins the *wiring* — which
 * source the grant is read from, and the one write this read path performs.
 */
class GetNotificationCaptureHealthUseCaseTest {

    private val now = 1_700_000_000_000L
    private val sixHours = ListenerHealth.DEAD_THRESHOLD_MILLIS

    private class FakeSource(
        override val sourceType: IngestSourceType,
        var status: IngestSourceStatus,
    ) : TransactionIngestSource {
        override suspend fun status(): IngestSourceStatus = status
    }

    private class FakeHealthStore(initial: ListenerHealthRecord) : ListenerHealthStore {
        private val state = MutableStateFlow(initial)
        var grantObservations: Int = 0
            private set

        override val record: Flow<ListenerHealthRecord> = state
        override suspend fun current(): ListenerHealthRecord = state.value
        override suspend fun recordConnected(atMillis: Long) {
            state.value = state.value.copy(connected = true, lastConnectedAt = atMillis)
        }

        override suspend fun recordDisconnected(atMillis: Long) {
            state.value = state.value.copy(connected = false, lastDisconnectedAt = atMillis)
        }

        override suspend fun recordGrantObserved(atMillis: Long) {
            grantObservations++
            if (state.value.grantObservedAt == null) {
                state.value = state.value.copy(grantObservedAt = atMillis)
            }
        }
    }

    private fun useCase(
        store: FakeHealthStore,
        sources: Set<TransactionIngestSource>,
        uptime: Long = ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS * 10,
    ) = GetNotificationCaptureHealthUseCase(
        getIngestSourceStatus = GetIngestSourceStatusUseCase(sources),
        healthStore = store,
        clock = Clock { now },
        processUptime = ProcessUptime { uptime },
    )

    /**
     * The answer comes from the **notification** source, not from whichever
     * source happens to be first in the set.
     *
     * The set is unordered and `smsFull` puts two things in it, so a use case
     * that read "the first source" would pass on a `playSafe` build and be
     * wrong on the owner's phone. The SMS source here is deliberately the
     * healthy one and the notification source the broken one, so reading the
     * wrong entry produces the wrong answer rather than the same one.
     */
    @Test
    fun invoke_readsTheNotificationSourceAndNotTheSmsOne() = runTest {
        val health = useCase(
            store = FakeHealthStore(ListenerHealthRecord()),
            sources = setOf(
                FakeSource(IngestSourceType.SMS, IngestSourceStatus.READY),
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.PERMISSION_REQUIRED),
            ),
        ).invoke()

        assertThat(health).isEqualTo(NotificationCaptureHealth.NOT_GRANTED)
    }

    /**
     * The one write on the read path, and it fires exactly when there is
     * nothing to measure from.
     */
    @Test
    fun invoke_withAHeldGrantAndNoHistory_stampsTheReference() = runTest {
        val store = FakeHealthStore(ListenerHealthRecord())

        useCase(
            store = store,
            sources = setOf(
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY),
            ),
        ).invoke()

        assertThat(store.grantObservations).isEqualTo(1)
        assertThat(store.current().grantObservedAt).isEqualTo(now)
    }

    /**
     * The common path — a healthy install, polled on every resume — performs no
     * write at all.
     *
     * Not a micro-optimisation: `recordGrantObserved` is idempotent in the
     * store, so a call here would be *correct* and would still rewrite a
     * preferences file on every resume of every screen that reads this. The
     * assertion is on the count, because a version that called it unconditionally
     * would pass every other test in this file.
     */
    @Test
    fun invoke_withHistoryAlreadyRecorded_doesNotWrite() = runTest {
        val store = FakeHealthStore(
            ListenerHealthRecord(connected = true, lastConnectedAt = now - 1000),
        )

        val health = useCase(
            store = store,
            sources = setOf(
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY),
            ),
        ).invoke()

        assertThat(health).isEqualTo(NotificationCaptureHealth.CONNECTED)
        assertThat(store.grantObservations).isEqualTo(0)
    }

    /** Without the grant there is nothing to reference, so nothing is stamped. */
    @Test
    fun invoke_withoutTheGrant_doesNotStampAReference() = runTest {
        val store = FakeHealthStore(ListenerHealthRecord())

        useCase(
            store = store,
            sources = setOf(
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.PERMISSION_REQUIRED),
            ),
        ).invoke()

        assertThat(store.grantObservations).isEqualTo(0)
    }

    /**
     * No notification source in the set at all.
     *
     * No shipping flavour does this — both bind one (D-04) — but the map lookup
     * is nullable and the honest answer to "we cannot find the source you asked
     * about" is to render nothing, not to claim a permission is missing.
     */
    @Test
    fun invoke_withNoNotificationSource_reportsUnavailable() = runTest {
        val health = useCase(
            store = FakeHealthStore(ListenerHealthRecord()),
            sources = setOf(FakeSource(IngestSourceType.SMS, IngestSourceStatus.READY)),
        ).invoke()

        assertThat(health).isEqualTo(NotificationCaptureHealth.UNAVAILABLE)
    }

    /** End to end through the real evaluation: a stale grant is the banner case. */
    @Test
    fun invoke_withAStaleRecord_reportsDead() = runTest {
        val health = useCase(
            store = FakeHealthStore(
                ListenerHealthRecord(lastConnectedAt = now - sixHours - 1),
            ),
            sources = setOf(
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY),
            ),
        ).invoke()

        assertThat(health).isEqualTo(NotificationCaptureHealth.DEAD)
    }

    /**
     * The banner clears the instant the listener binds, without a resume.
     *
     * This is the half a poll cannot cover, and it is the *end* of the happy
     * path rather than an edge case: the user grants access in system Settings,
     * comes back (the poll), and the system binds the listener a moment later
     * while Home is already on screen. Nothing else would tell it.
     *
     * The record starts stale enough to be DEAD so the assertion has somewhere
     * to move from — an observation test that starts healthy proves only that
     * the flow emits.
     */
    @Test
    fun observe_reEmitsWhenTheListenerBinds() = runTest {
        val store = FakeHealthStore(
            ListenerHealthRecord(lastConnectedAt = now - sixHours - 1),
        )
        val subject = useCase(
            store = store,
            sources = setOf(
                FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY),
            ),
        )
        assertThat(subject.observe().first()).isEqualTo(NotificationCaptureHealth.DEAD)

        store.recordConnected(now)

        assertThat(subject.observe().first()).isEqualTo(NotificationCaptureHealth.CONNECTED)
    }

    /**
     * The grant is re-read on each emission, not captured when `observe` was
     * called.
     *
     * Otherwise a listener that binds *after* the user revoked access would
     * report CONNECTED — a "working" banner for a permission that is gone, which
     * is the one wrong answer this whole use case exists to prevent.
     */
    @Test
    fun observe_rereadsTheGrantOnEachEmission() = runTest {
        val store = FakeHealthStore(ListenerHealthRecord())
        val source = FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY)
        val subject = useCase(store = store, sources = setOf(source))

        source.status = IngestSourceStatus.PERMISSION_REQUIRED
        store.recordConnected(now)

        assertThat(subject.observe().first()).isEqualTo(NotificationCaptureHealth.NOT_GRANTED)
    }
}
