package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import javax.inject.Inject

/**
 * Every capture source's current state, in one call (SPEC.md §3.1, §5.2).
 *
 * The source-agnostic consumer the abstraction exists for, and the reason it
 * lives here rather than in `:feature:ingest`: features may depend on `:core:*`
 * only and never on another feature (CLAUDE.md §3), so a Dashboard health
 * banner or a Settings row could not reach a registry that lived beside the
 * adapters. The adapters bind themselves into the set from `:feature:ingest`;
 * `:app` assembles the graph; every consumer sees this.
 *
 * There is no branch on [IngestSourceType] anywhere in it — that is the point.
 * `smsFull` and `playSafe` differ only in *what the set contains and what it
 * says*, never in what the caller does with it.
 */
public class GetIngestSourceStatusUseCase @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards TransactionIngestSource>,
) {

    /**
     * Keyed by source type, which assumes one source per type — true by
     * construction (each flavour binds exactly one SMS source and one
     * notification source) and worth the convenience at every call site, which
     * otherwise has to search a list for the row it wants to render.
     *
     * Statuses are read concurrently in neither direction: there are two of
     * them and each is a binder call, so a sequential read costs less than the
     * coroutines to parallelise it.
     */
    public suspend operator fun invoke(): Map<IngestSourceType, IngestSourceStatus> =
        sources.associate { source -> source.sourceType to source.status() }
}
