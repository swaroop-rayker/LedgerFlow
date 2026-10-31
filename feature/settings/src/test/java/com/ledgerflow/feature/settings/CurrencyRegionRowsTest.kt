package com.ledgerflow.feature.settings

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Currency & region (V2-PLAN §3.3, D4): the base currency is shown
 * and cannot be tapped, grouping is chosen in place with a sample in the base
 * currency, and date order opens a picker whose options are written dates.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// PHONE_2X's height: the Settings tab is a lazy list (design-system step D2), so
// only what fits is composed, and every section has to fit for these checks.
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_2X)
class CurrencyRegionRowsTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<SettingsEvent>()

    private fun show(state: SettingsUiState) {
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = SettingsPage.Region,
                    state = state,
                    onBack = {},
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onBudgetAlerts = {},
                    onPaymentApps = {},
                    onDataPermissions = {},
                    onEvent = { events += it },
                )
            }
        }
    }

    @Test
    fun baseCurrency_isShown_andIsNotAControl() {
        show(SettingsUiState(isLoaded = true, baseCurrency = "INR"))
        rule.onNodeWithText("Base currency").performScrollTo()
        rule.onNodeWithText(baseCurrencySubtitle(SettingsUiState(baseCurrency = "INR")))
            .assertHasNoClickAction()
        assertThat(baseCurrencySubtitle(SettingsUiState(baseCurrency = "INR")))
            .isEqualTo("₹ Indian Rupee (INR). Every amount is kept in it, so it can't change.")
    }

    @Test
    fun grouping_sendsItsChoice() {
        show(SettingsUiState(isLoaded = true, baseCurrency = "INR"))
        mapOf(
            "Currency" to NumberGrouping.BY_CURRENCY,
            "Lakh" to NumberGrouping.LAKH,
            "Western" to NumberGrouping.WESTERN,
        ).forEach { (label, grouping) ->
            events.clear()
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.waitForIdle()
            assertWithMessage("tapping \"$label\"").that(events).containsExactly(SettingsEvent.GroupingPicked(grouping))
        }
    }

    @Test
    fun groupingSample_isInTheBaseCurrency_asTheChoiceWritesIt() {
        fun sample(code: String, grouping: NumberGrouping) =
            groupingSubtitle(SettingsUiState(baseCurrency = code, region = RegionConventions(grouping = grouping)))

        assertThat(sample("INR", NumberGrouping.BY_CURRENCY)).isEqualTo("₹12,34,567.00, as rupees are written.")
        assertThat(sample("INR", NumberGrouping.WESTERN)).isEqualTo("₹1,234,567.00, in thousands and millions.")
        assertThat(sample("USD", NumberGrouping.LAKH)).isEqualTo("$12,34,567.00, in lakh and crore.")
        // A currency with no minor unit still shows 1,234,567 of it, not 100 times that.
        assertThat(sample("JPY", NumberGrouping.WESTERN)).startsWith("¥1,234,567,")
    }

    @Test
    fun dateOrder_opensAPicker_whoseOptionsAreWrittenDates() {
        assertThat(dateOrderOptions()).containsExactly(
            "Region style",
            "24 Oct 2026",
            "Oct 24, 2026",
            "2026 Oct 24",
        ).inOrder()

        show(
            SettingsUiState(
                isLoaded = true,
                choosingDateOrder = true,
                region = RegionConventions(dateOrder = DateOrder.MONTH_DAY_YEAR),
            ),
        )
        rule.onNodeWithText("Oct 24, 2026").assertIsSelected()
        rule.onNodeWithText("2026 Oct 24").performClick()
        rule.waitForIdle()
        assertThat(events).containsExactly(SettingsEvent.DateOrderPicked(DateOrder.YEAR_MONTH_DAY))
    }

    @Test
    fun dateOrderRow_saysWhatTheChoiceWrites() {
        assertThat(dateOrderSubtitle(DateOrder.DAY_MONTH_YEAR)).isEqualTo("Day first: 24 Oct 2026")
        assertThat(dateOrderSubtitle(DateOrder.YEAR_MONTH_DAY)).isEqualTo("Year first: 2026 Oct 24")
        assertThat(dateOrderSubtitle(DateOrder.AUTO)).startsWith("Your region's style: ")
    }
}
