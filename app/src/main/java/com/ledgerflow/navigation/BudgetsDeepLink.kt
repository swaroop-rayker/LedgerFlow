package com.ledgerflow.navigation

/**
 * `ledgerflow://budgets` -> [Destination.Budgets] (BUG75): where a budget
 * alert's tap goes. Built in `:feature:budget`, routed here, for the reason
 * [InboxDeepLink] gives: the route belongs to `:app`, and features never
 * depend on features.
 *
 * Only the bare link. Anything after the host is not a link this app wrote,
 * so it is null and the user stays where they were.
 */
internal object BudgetsDeepLink {

    const val LINK: String = "ledgerflow://budgets"

    fun parse(uri: String?): Destination? {
        val path = uri?.trim()?.substringBefore('?')?.substringBefore('#') ?: return null
        return if (path == LINK || path == "$LINK/") Destination.Budgets else null
    }
}
