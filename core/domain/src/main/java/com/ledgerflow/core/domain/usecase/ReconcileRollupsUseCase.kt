package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.RollupRepository
import javax.inject.Inject

/**
 * The nightly rollup reconciliation (ADR-0006, SPEC.md §5.6).
 *
 * @return buckets repaired — zero on a healthy install.
 */
public class ReconcileRollupsUseCase @Inject constructor(
    private val rollups: RollupRepository,
) {
    public suspend operator fun invoke(): Int = rollups.reconcile()
}

/**
 * The cold-cache fill (ADR-0006), run once per install.
 *
 * Separate from [ReconcileRollupsUseCase] because the two answer different
 * questions with the same routine: reconciliation is for *drift*, and can wait
 * for the device to be idle and charging; this is for a rollup that has never
 * been built, and the whole Analytics screen is wrong until it runs.
 */
public class BackfillRollupsUseCase @Inject constructor(
    private val rollups: RollupRepository,
) {
    public suspend operator fun invoke(): Int = rollups.backfillIfNeverReconciled()
}
