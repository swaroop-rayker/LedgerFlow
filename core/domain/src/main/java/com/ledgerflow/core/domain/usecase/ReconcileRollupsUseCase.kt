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
