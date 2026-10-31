package com.ledgerflow.core.designsystem.format

import com.ledgerflow.core.model.Money

/**
 * A budget in words, as what is left of it or how far over it is (step F5,
 * the canvas's "250 left of 5,000"): the headroom is what someone acts on.
 *
 * One phrasing for a budget wherever the app shows one -- Home and Budgets
 * both read it from here (owner, 2026-10-30). The arithmetic is `Money`'s, so
 * no amount is a bare `Long` sum here (`CLAUDE.md` Law 3).
 */
public object BudgetWords {

    /** "₹250.00 left of ₹5,000.00", or "₹300.00 over ₹5,000.00". */
    public fun headroom(spent: Money, budget: Money, currencyCode: String): String {
        val limit = MoneyFormat.symbolised(budget.minor, currencyCode)
        return if (spent <= budget) {
            MoneyFormat.symbolised((budget - spent).minor, currencyCode) + " left of " + limit
        } else {
            MoneyFormat.symbolised((spent - budget).minor, currencyCode) + " over " + limit
        }
    }
}
