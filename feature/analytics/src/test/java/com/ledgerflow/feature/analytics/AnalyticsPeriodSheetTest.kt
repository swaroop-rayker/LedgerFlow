package com.ledgerflow.feature.analytics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The period sheet and the pill that opens it (step F5), which replaced the
 * row of range chips: every range is still one choice away, the one in force
 * is marked, and Custom still asks rather than selecting (BUG21).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AnalyticsPeriodSheetTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theSheet_listsEveryRange_marksTheCurrentOne_andSendsTheChoice() {
        val events = mutableListOf<AnalyticsEvent>()
        rule.setContent {
            LfTheme {
                AnalyticsPeriodSheet(state = AnalyticsUiState(range = AnalyticsRange.MONTH), onEvent = { events += it })
            }
        }
        AnalyticsRange.entries.forEach { rule.onNodeWithText(periodName(it)).assertExists() }
        rule.onNodeWithText("Current").assertExists()

        rule.onNodeWithText("3 months").performClick()
        assertThat(events).containsExactly(AnalyticsEvent.RangeSelected(AnalyticsRange.THREE_MONTHS))
    }

    @Test
    fun thePill_namesThePeriod_andACustomOneByItsDates() {
        assertThat(periodPillLabel(AnalyticsRange.SIX_MONTHS, null, null)).isEqualTo("6 months")
        // Custom with no dates yet reads as its name, never as a made-up window.
        assertThat(periodPillLabel(AnalyticsRange.CUSTOM, null, null)).isEqualTo("Custom range")

        val dash = " " + Char(0x2013) + " "
        val aug3 = LocalDate.of(2026, 8, 3).toEpochDay().toInt()
        val aug19 = LocalDate.of(2026, 8, 19).toEpochDay().toInt()
        assertThat(periodPillLabel(AnalyticsRange.CUSTOM, aug3, aug19)).isEqualTo("3 Aug" + dash + "19 Aug")

        // Across a new year the years are said, or "20 Dec – 5 Jan" is ambiguous.
        val dec20 = LocalDate.of(2025, 12, 20).toEpochDay().toInt()
        val jan5 = LocalDate.of(2026, 1, 5).toEpochDay().toInt()
        assertThat(periodPillLabel(AnalyticsRange.CUSTOM, dec20, jan5)).contains("2025")
        assertThat(periodPillLabel(AnalyticsRange.CUSTOM, dec20, jan5)).contains("2026")
    }
}
