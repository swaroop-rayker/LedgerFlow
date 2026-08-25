package com.ledgerflow.feature.ingest.di

import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.feature.ingest.adapters.NotificationAdapter
import com.ledgerflow.feature.ingest.adapters.SmsAdapter
import com.ledgerflow.feature.ingest.pipeline.DiscardingIngestEventSink
import com.ledgerflow.feature.ingest.pipeline.IngestEventSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

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
     * S11's sink drops what it is given; P2 swaps this one line for the
     * raw-row write plus `ParseIngestWorker`, and no adapter changes.
     */
    @Binds
    internal abstract fun ingestEventSink(sink: DiscardingIngestEventSink): IngestEventSink
}
