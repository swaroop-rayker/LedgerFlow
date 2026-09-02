package com.ledgerflow.core.domain.analytics

/**
 * The rollup maintenance port (ADR-0006, SPEC.md §5.6).
 *
 * Only reconciliation is exposed. The *incremental* half of ADR-0006 is not a
 * separate operation anyone can invoke — it happens inside the approval,
 * soft-delete and restore transactions, because a rollup update that could be
 * called independently is a rollup update that can be forgotten. There is
 * deliberately no `recomputeDay()` on this interface.
 */
public interface RollupRepository {

    /**
     * Rebuild every bucket in both books from the base tables, and report how
     * many were wrong.
     *
     * **The base tables win, unconditionally.** `daily_rollup` is a cache with a
     * primary key: it holds no information absent from `ledger_entry` and
     * `line_item`, so a disagreement is a rollup bug by definition, never a
     * ledger bug. Nothing here writes to `ledger_entry` — that would be a fifth
     * writer and a Law 1 violation, and there is no scenario in which the
     * derived table knows something its source does not.
     *
     * @return buckets repaired. Zero on a healthy install; anything else is a
     *   bug in the incremental path, which is why it is recorded rather than
     *   swallowed.
     */
    public suspend fun reconcile(): Int
}
