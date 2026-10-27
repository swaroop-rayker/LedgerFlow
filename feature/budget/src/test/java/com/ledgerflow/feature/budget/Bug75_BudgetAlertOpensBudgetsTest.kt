package com.ledgerflow.feature.budget

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.usecase.BudgetAlert
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BUG75: a budget alert had no content intent, so tapping it did nothing; it
 * could only be swiped away. It opens Budgets now, through
 * `ledgerflow://budgets`, aimed at this app's package only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Bug75_BudgetAlertOpensBudgetsTest {

    @Test
    fun anAlert_tapsThroughToBudgets() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BudgetNotifications.ensureChannel(app)

        BudgetNotifications.post(app, BudgetAlert.ThresholdCrossed(groceries, 80), "INR")

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertThat(tap.dataString).isEqualTo(BudgetNotifications.BUDGETS_LINK)
        assertThat(tap.`package`).isEqualTo(app.packageName)
    }

    private val groceries = BudgetProgress(
        budget = Budget(
            id = "b1",
            categoryId = "groceries",
            subcategoryId = null,
            period = BudgetPeriod.MONTHLY,
            amount = Money(10_000_00L),
            startDate = START,
            rolloverEnabled = false,
            alertThresholds = listOf(80, 100),
        ),
        categoryName = "Groceries",
        categoryColorArgb = null,
        spent = Money(8_200_00L),
        periodStart = START,
        periodEnd = START + 29,
        daysElapsed = 20,
        projectedSpend = Money(12_300_00L),
    )

    private companion object {
        const val START = 20_667
    }
}
