package com.ledgerflow.navigation

import android.content.Context
import com.ledgerflow.core.domain.analytics.BudgetAlertTrigger
import com.ledgerflow.feature.budget.work.BudgetAlertWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * §5.7's alert trigger, wired where both halves are visible.
 *
 * `:core:domain` declares the port and stays Android-free; `:feature:budget`
 * owns the Worker; neither may depend on the other (CLAUDE.md §3), so `:app`
 * -- the module that wires everything and holds no business logic -- is the
 * only place this binding can live.
 */
@Module
@InstallIn(SingletonComponent::class)
public object BudgetAlertModule {

    @Provides
    @Singleton
    public fun budgetAlertTrigger(
        @ApplicationContext context: Context,
    ): BudgetAlertTrigger = BudgetAlertTrigger {
        // Enqueue only. Evaluating here would put a database read on whatever
        // thread just approved an entry, which on the Inbox path is the main
        // one -- and StrictMode kills the debug build for exactly that.
        BudgetAlertWorker.enqueue(context)
    }
}
