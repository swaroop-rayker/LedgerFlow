package com.ledgerflow.core.model

/**
 * A budget's repeat interval (SPEC.md §5.7).
 *
 * Stored by `name`, never by ordinal — reordering this enum would silently
 * re-point every existing row, and a budget that quietly changes from monthly
 * to weekly is a wrong number on the Dashboard with no event to trace it to.
 */
public enum class BudgetPeriod {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY,
}
