package com.ledgerflow.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Budgets at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * Two budgets — one comfortably under, one **on course to overrun**, whose
 * warning line is the sentence this screen exists to say — and the empty
 * screen. The screen had one preview, of no budgets at all.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class BudgetScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: BudgetUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { BudgetScreen(state = state, onEvent = {}) }
        }

    private fun progress(id: String, name: String, limit: Long, spent: Long, projected: Long) = BudgetProgress(
        budget = Budget(
            id = id,
            categoryId = id,
            subcategoryId = null,
            period = BudgetPeriod.MONTHLY,
            amount = Money(limit),
            startDate = START,
            rolloverEnabled = false,
            alertThresholds = listOf(80, 100),
        ),
        categoryName = name,
        categoryColorArgb = 0xFF3E6AD6.toInt(),
        spent = Money(spent),
        periodStart = START,
        periodEnd = START + 29,
        daysElapsed = 18,
        projectedSpend = Money(projected),
    )

    private val two = BudgetUiState(
        isLoading = false,
        budgets = listOf(
            progress("g", "Groceries", limit = 10_000_00, spent = 7_200_00, projected = 12_000_00),
            progress("d", "Dining out", limit = 4_000_00, spent = 1_100_00, projected = 1_830_00),
        ),
    )

    private val none = BudgetUiState(isLoading = false)

    @Test fun two_1x() = capture("budgets-two-1x", two, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun two_2x() = capture("budgets-two-2x", two, 2f)

    @Test fun none_1x() = capture("budgets-none-1x", none, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun none_2x() = capture("budgets-none-2x", none, 2f)

    private companion object {
        const val START = 20_667
    }
}
