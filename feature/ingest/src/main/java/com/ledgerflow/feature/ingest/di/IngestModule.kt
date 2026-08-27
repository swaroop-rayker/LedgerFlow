package com.ledgerflow.feature.ingest.di

import android.content.Context
import androidx.work.WorkManager
import com.ledgerflow.core.domain.ingest.IngestWorkTrigger
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.feature.ingest.adapters.NotificationAdapter
import com.ledgerflow.feature.ingest.adapters.SmsAdapter
import com.ledgerflow.feature.ingest.pipeline.IngestEventSink
import com.ledgerflow.feature.ingest.pipeline.PersistingIngestEventSink
import com.ledgerflow.feature.ingest.work.WorkManagerIngestWorkTrigger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Where the flavour difference actually lives, and the only place it does
 * (SPEC.md §3.1, D-04).
 *
 * This module is in `src/main` — shared, one copy, no flavour variant of it —
 * and it binds both sources unconditionally. [SmsAdapter] resolves to the
 * `smsFull` implementation or the `playSafe` no-op depending on which source set
 * the compiler sees, so the two builds differ in what the set *contains* rather
 * than in any code path. There is no `if`, no `@Suppress`, and nothing for a
 * consumer to know: [com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase]
 * injects the set and asks each member the same question.
 *
 * A multibinding rather than two named bindings because the set is the thing
 * that grows: P4's OCR pipeline is a third capture source with the same shape,
 * and adding it should be one `@Binds @IntoSet` and no edit to any consumer.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IngestModule {

    /** Both flavours. The higher-recall source (§3.1). */
    @Binds
    @IntoSet
    internal abstract fun notificationSource(adapter: NotificationAdapter): TransactionIngestSource

    /** `smsFull`'s real adapter, or `playSafe`'s inert one. Same name, one binding. */
    @Binds
    @IntoSet
    internal abstract fun smsSource(adapter: SmsAdapter): TransactionIngestSource

    /**
     * The one line P2 swapped to make capture real.
     *
     * S11 bound a sink that discarded, so the adapters, the `goAsync()` shape
     * and the Hilt graph were all exercised on device before anything was
     * persisted. Neither adapter changed when this became the persisting one --
     * which is what the [IngestEventSink] seam existed to buy.
     */
    @Binds
    internal abstract fun ingestEventSink(sink: PersistingIngestEventSink): IngestEventSink

    /**
     * The port `:app` uses to ask for a pass at launch (§16 Q14).
     *
     * A domain port rather than the concrete class, because the caller is
     * `AppViewModel` -- which is unit-tested, and no JVM unit test has a
     * `WorkManager`.
     */
    @Binds
    internal abstract fun ingestWorkTrigger(
        trigger: WorkManagerIngestWorkTrigger,
    ): IngestWorkTrigger
}

/**
 * [WorkManager] itself.
 *
 * Not bound by hilt-work, which supplies the *factory* that constructs workers,
 * not the manager that schedules them. `getInstance` is the only supported way
 * to obtain it, and it must not be called before
 * `LedgerFlowApplication.workManagerConfiguration` exists -- which is why the
 * sink injects a `Provider` and resolves this lazily rather than at graph
 * construction.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object IngestWorkModule {

    @Provides
    @Singleton
    internal fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
