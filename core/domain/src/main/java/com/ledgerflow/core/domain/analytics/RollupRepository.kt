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

    /**
     * Fill the rollup **once**, if it has never been reconciled.
     *
     * `MIGRATION_8_9` creates `daily_rollup` empty and deliberately does not
     * backfill it, on the argument that an empty rollup is a cold cache and the
     * nightly pass will fill it. That argument had a hole: the nightly pass
     * requires the device to be **idle and charging**, correctly, because
     * reconciliation rewrites the whole table and nothing is waiting on it —
     * except that on a cold cache something *is* waiting on it, namely every
     * figure on the Analytics screen for every entry approved before the
     * migration ran.
     *
     * Observed on the owner's device: two credits of ₹6,300 and ₹250, plainly
     * visible in the Ledger, reported by D1 as "In ₹0.00". The incremental path
     * had kept every *later* entry correct, so the screen looked right and was
     * silently missing the older half.
     *
     * This is the cold-cache fill, and it is the same routine — ADR-0006 exists
     * to keep the recompute singular, so this decides *whether* to run it and
     * never reimplements it. It is a no-op forever after the first run.
     *
     * @return buckets written, or zero when reconciliation had already run.
     */
    public suspend fun backfillIfNeverReconciled(): Int
}
