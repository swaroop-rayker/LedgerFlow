package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import org.junit.Test

/**
 * BUG75's route: the link a budget alert opens lands on Budgets, and nothing
 * else under that host is taken for it. The link is built in `:feature:budget`
 * and parsed here, so the two spellings are held equal by a test rather than
 * by hoping.
 */
class Bug75_BudgetsDeepLinkTest {

    @Test
    fun theAlertsLink_opensBudgets() {
        assertThat(BudgetsDeepLink.parse(BudgetNotifications.BUDGETS_LINK)).isEqualTo(Destination.Budgets)
        assertThat(BudgetsDeepLink.LINK).isEqualTo(BudgetNotifications.BUDGETS_LINK)
    }

    @Test
    fun aTrailingSlashOrQuery_stillOpensBudgets() {
        assertThat(BudgetsDeepLink.parse("ledgerflow://budgets/")).isEqualTo(Destination.Budgets)
        assertThat(BudgetsDeepLink.parse("ledgerflow://budgets?from=alert")).isEqualTo(Destination.Budgets)
    }

    @Test
    fun anythingElse_isNotOurs() {
        assertThat(BudgetsDeepLink.parse("ledgerflow://budgets/b1")).isNull()
        assertThat(BudgetsDeepLink.parse("ledgerflow://inbox")).isNull()
        assertThat(BudgetsDeepLink.parse(null)).isNull()
    }

    /** The Inbox's parser does not claim this host either. */
    @Test
    fun theInboxParser_leavesItAlone() {
        assertThat(InboxDeepLink.parse(BudgetNotifications.BUDGETS_LINK)).isNull()
    }
}
