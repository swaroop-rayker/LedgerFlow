package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ListenerHealth
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the Dashboard banner and the Settings row say about notification capture
 * (SPEC.md §5.2).
 *
 * **The grant half has to be polled, and that is §5.2's own instruction:**
 * "polls `getEnabledListenerPackages()` on resume to confirm". It lives in
 * system Settings, so the app is told nothing when it changes — the user leaves
 * for the settings page, grants access, and comes back, and a resume is the only
 * moment the app can learn what happened. A `Flow` over that source would be a
 * `Flow` that lies for the whole of the interesting interval.
 *
 * **The liveness half genuinely is observable, and [observe] is how the banner
 * gets to clear itself without waiting.** Both are needed and neither is
 * redundant: a poll cannot see a rebind that happens while the user is looking
 * at the screen, and an observation cannot see a grant changed in another app.
 * The happy path needs both in sequence — the user grants access in Settings
 * (caught by the resume poll), returns, and the listener binds a moment later
 * while Home is on screen (caught by the observation). Without the second half
 * the banner would still be there, telling them to do what they just did.
 */
public class GetNotificationCaptureHealthUseCase @Inject constructor(
    private val getIngestSourceStatus: GetIngestSourceStatusUseCase,
    private val healthStore: ListenerHealthStore,
    private val clock: Clock,
    private val processUptime: ProcessUptime,
) {

    public suspend operator fun invoke(): NotificationCaptureHealth =
        evaluate(healthStore.current())

    /**
     * Re-evaluated whenever the liveness record changes.
     *
     * The grant is re-read on each emission rather than captured once. Emissions
     * are rare — a bind or an unbind — so the extra binder call costs nothing,
     * and reading a stale grant here would produce exactly the confusing pair
     * this use case exists to avoid: a "connected" answer for a permission that
     * has since been revoked.
     */
    public fun observe(): Flow<NotificationCaptureHealth> =
        healthStore.record.map { record -> evaluate(record) }

    private suspend fun evaluate(record: ListenerHealthRecord): NotificationCaptureHealth {
        val status = getIngestSourceStatus()[IngestSourceType.NOTIFICATION]
        // Absent means no notification source is bound into the set at all,
        // which no shipping flavour does -- both bind one (D-04). Treating it as
        // UNAVAILABLE renders nothing, which is the honest answer to "we cannot
        // find the source you are asking about".
            ?: return NotificationCaptureHealth.UNAVAILABLE

        val now = clock.nowMillis()

        // The reference point of last resort, stamped the first time a held
        // grant is seen with no history behind it. Without it, a grant that is
        // honoured in Settings and never actually bound has nothing to measure
        // from, and would read as healthy forever.
        //
        // Idempotent in the store, but gated here as well so the common path --
        // every poll, on a healthy install -- does not perform a write to
        // discover it has nothing to write.
        if (status == IngestSourceStatus.READY && record.lastKnownAliveAt == null) {
            healthStore.recordGrantObserved(now)
        }

        return ListenerHealth.evaluate(
            status = status,
            record = record,
            nowMillis = now,
            processUptimeMillis = processUptime.uptimeMillis(),
        )
    }
}
