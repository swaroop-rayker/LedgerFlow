package com.ledgerflow.feature.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The merchant entries page (V2-6) at 1x, 2x and RTL, with Analytics' filters
 * off and on, in both themes. **Review the diff; never re-record blind.**
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class MerchantEntriesGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 31).toEpochDay().toInt()

    private fun entry(id: String, daysAgo: Int, minor: Long, category: String, color: Long, note: String? = null) =
        LedgerListItem(
            id = id, ledger = LedgerType.DEBIT, amount = Money(minor), currency = "INR",
            // A real evening on that day: a zero timestamp printed "1 Jan, 12:00 AM" on every row.
            occurredAt = (today - daysAgo) * MILLIS_PER_DAY + EVENING_MILLIS,
            localDate = today - daysAgo, categoryName = category, categoryColorArgb = color.toInt(),
            // As the page's ViewModel hands rows over: the title names the merchant.
            merchantName = null, note = note,
        )

    private val rows = listOf(
        entry("1", 3, 62_000, "Dining out", 0xFFE07B39, "dinner"),
        entry("2", 10, 41_000, "Dining out", 0xFFE07B39),
        entry("3", 17, 54_000, "Groceries", 0xFF2E9E6B, "Instamart"),
        entry("4", 22, 33_000, "Dining out", 0xFFE07B39),
        entry("5", 28, 24_000, "Dining out", 0xFFE07B39),
    )

    private fun state(filtered: Boolean) = MerchantEntriesUiState(
        ledger = LedgerType.DEBIT,
        merchantName = "Swiggy",
        from = today - 29,
        to = today,
        analyticsFiltered = filtered,
        total = MerchantTotal(Money.sum(rows.map { it.amount }), rows.size),
    )

    private fun capture(name: String, filtered: Boolean, fontScale: Float, rtl: Boolean = false) =
        rule.captureScreenGolden(name, fontScale, if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            LfTheme {
                LfScaffold {
                    MerchantEntriesScreen(
                        state = state(filtered),
                        // Finished load states, stated, as the Ledger's goldens do: a
                        // static PagingData without them reports Loading forever.
                        entries = flowOf(PagingData.from(rows, sourceLoadStates = LoadStates(DONE, DONE, DONE))),
                        onBack = {},
                    )
                }
            }
        }

    @Test fun page_1x() = capture("merchant-entries-1x", filtered = false, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun page_2x() = capture("merchant-entries-2x", filtered = false, 2f)

    @Test fun page_1x_rtl() = capture("merchant-entries-1x-rtl", filtered = false, 1f, rtl = true)

    /** Analytics had filters on: the page says it does not apply them. */
    @Test fun filtered_1x() = capture("merchant-entries-filtered-1x", filtered = true, 1f)

    private companion object {
        val DONE = LoadState.NotLoading(endOfPaginationReached = true)
        const val MILLIS_PER_DAY = 86_400_000L
        const val EVENING_MILLIS = 13L * 3_600_000L + 30L * 60_000L
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MerchantEntriesGoldensDarkTest : MerchantEntriesGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
