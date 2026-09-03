package com.ledgerflow.core.domain.analytics

/**
 * Something that should re-evaluate budget alerts because spending changed
 * (SPEC.md §5.7).
 *
 * A port rather than a direct call, for the ordinary reason: §5.7's alerts are
 * a WorkManager job in `:feature:budget`, and `:core:domain` is Android-free
 * while `:feature:*` may not depend on `:feature:*` (CLAUDE.md §3). The
 * implementation is wired in `:app`, which is the module that knows both.
 *
 * A `fun interface`, so `:app` can supply it as a lambda -- there is one
 * method and no state, and a named class would be ceremony around one call.
 *
 * **Fired from the use cases that increase spend, not from every ledger
 * write.** Approving adds spending and restoring puts it back; soft-delete and
 * purge only ever reduce it, and a *reduction* cannot newly cross a threshold
 * upward. Firing on those would evaluate for nothing.
 */
public fun interface BudgetAlertTrigger {

    /** Cheap and fire-and-forget: implementations enqueue, they do not evaluate. */
    public fun onSpendingChanged()
}

/** The default where nothing is wired -- tests, and `:core:*` unit tests. */
public object NoOpBudgetAlertTrigger : BudgetAlertTrigger {
    override fun onSpendingChanged(): Unit = Unit
}
