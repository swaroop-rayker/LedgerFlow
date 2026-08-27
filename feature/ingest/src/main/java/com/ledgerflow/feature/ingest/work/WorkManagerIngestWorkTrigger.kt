package com.ledgerflow.feature.ingest.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ledgerflow.core.domain.ingest.IngestWorkTrigger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * [IngestWorkTrigger] over WorkManager — the only implementation.
 *
 * `Provider<WorkManager>` rather than the instance: `WorkManager.getInstance`
 * touches disk on first call, and resolving it eagerly would put that on
 * whatever thread built the Hilt graph. It must also not be called before
 * `LedgerFlowApplication.workManagerConfiguration` exists.
 */
@Singleton
public class WorkManagerIngestWorkTrigger @Inject constructor(
    private val workManager: Provider<WorkManager>,
) : IngestWorkTrigger {

    /**
     * One pass, however many callers asked for it.
     *
     * [ExistingWorkPolicy.KEEP] on a unique name: a burst of five bank SMS in a
     * second should produce one worker run over five rows, not five runs racing
     * each other over the same table. A run already in flight keeps going and
     * picks up whatever landed while it worked; if it has already passed them,
     * the next enqueue starts a fresh one.
     *
     * That policy is also what makes it safe for app launch to call this
     * unconditionally, which it does so that §16 Q14's re-triage runs when the
     * allowlist changes rather than when the next message happens to arrive.
     */
    override fun requestParsePass() {
        workManager.get().enqueueUniqueWork(
            ParseIngestWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ParseIngestWorker>().build(),
        )
    }
}
