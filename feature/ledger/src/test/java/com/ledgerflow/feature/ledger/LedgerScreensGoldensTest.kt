package com.ledgerflow.feature.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Ledger and the bin at font scale 1.0 and 2.0 (§12, P5 step 4), each
 * capture also running §9.6's touch-target and label checks.
 *
 * - **Expenses** — one row per date band, an unsaved draft above them, rows
 *   with and without a merchant, a category or a note: every shape a row has.
 * - **Income** — the other book, so its colour and `+` are pinned, not assumed.
 * - **Empty** — a book never used.
 * - **Bin** — one entry from each book, the one screen that shows both
 *   (ADR-0015), with one selected so the erase controls are on screen.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class LedgerScreensGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun ledger(name: String, state: LedgerUiState, rows: List<LedgerListItem>, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme {
                LedgerScreen(
                    state = state,
                    // Finished load states, stated: a static `PagingData` without
                    // them reports `refresh = Loading` forever (measured), and the
                    // empty state waits for a settled load -- the first two
                    // recordings of an empty book were blank pages.
                    entries = flowOf(PagingData.from(rows, sourceLoadStates = LoadStates(DONE, DONE, DONE))),
                    onEvent = {},
                    onOpenDraft = {},
                    onReviewCandidate = {},
                )
            }
        }

    private fun bin(name: String, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { BinScreen(state = binState, onEvent = {}, onBack = {}) }
        }

    private fun item(
        id: String,
        minor: Long,
        dayOffset: Int,
        merchant: String? = null,
        category: String? = null,
        note: String? = null,
    ) = LedgerListItem(
        id = id,
        ledger = LedgerType.DEBIT,
        amount = Money(minor),
        currency = "INR",
        occurredAt = NOW - dayOffset * DAY + 10 * HOUR,
        localDate = TODAY - dayOffset,
        categoryName = category,
        categoryColorArgb = category?.let { CATEGORY_BLUE },
        merchantName = merchant,
        note = note,
    )

    private val expenses = listOf(
        item("1", 1_240_50, 0, "Big Bazaar", "Groceries"),
        item("2", 60_00, 0, category = "Transport", note = "Auto to office"),
        item("3", 1_24_000_00, 1, "Landlord", "Rent"),
        item("4", 349_00, 3, note = "Cash, no receipt"),
        item("5", 2_100_00, 12, "Croma", "Electronics"),
    )

    /** The other book, as the screen's preview builds it. */
    private val income = listOf(
        item("6", 85_000_00, 0, "Acme Corp", "Salary"),
        item("7", 2_400_00, 2, category = "Interest"),
    ).map { it.copy(ledger = LedgerType.CREDIT) }

    private fun state(ledger: LedgerType, hasAny: Boolean, unsaved: List<UnsavedRow> = emptyList()) =
        LedgerUiState(
            ledger = ledger,
            today = TODAY,
            hasAnyEntries = hasAny,
            windowDays = 30,
            unsaved = unsaved,
            isLoaded = true,
        )

    private val draft = UnsavedRow.Draft(
        DraftSummary(
            id = "d1",
            ledger = LedgerType.DEBIT,
            amount = Money(450_00),
            categoryName = "Dining",
            categoryColorArgb = CATEGORY_BLUE,
            merchantName = null,
            updatedAt = NOW,
            datedAt = NOW,
        ),
    )

    private val binEntries = listOf(
            DeletedEntry(
                id = "b1",
                ledger = LedgerType.DEBIT,
                amount = Money(1_240_50),
                currency = "INR",
                occurredAt = NOW - 2 * DAY,
                deletedAt = NOW - DAY,
                categoryName = "Groceries",
                categoryColorArgb = CATEGORY_BLUE,
                subcategoryName = "Staples",
                merchantName = "Big Bazaar",
                note = null,
            ),
            DeletedEntry(
                id = "b2",
                ledger = LedgerType.CREDIT,
                amount = Money(2_400_00),
                currency = "INR",
                occurredAt = NOW - 5 * DAY,
                deletedAt = NOW - DAY,
                categoryName = "Interest",
                categoryColorArgb = CATEGORY_BLUE,
                subcategoryName = null,
                merchantName = null,
                note = "Duplicate of the bank's credit",
            ),
    )

    /**
     * One row ticked, keyed the way the bin keys it -- book and id together
     * (`selectionKey`). The first recording used the bare id, and the header
     * said "1 of 2 selected" over two empty boxes; the review caught it.
     */
    private val binState = BinUiState(
        entries = binEntries,
        selected = setOf(binEntries.first().selectionKey()),
        isLoaded = true,
    )

    @Test fun expenses_1x() = ledger("ledger-expenses-1x", state(LedgerType.DEBIT, true, listOf(draft)), expenses, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun expenses_2x() = ledger("ledger-expenses-2x", state(LedgerType.DEBIT, true, listOf(draft)), expenses, 2f)

    @Test fun income_1x() = ledger("ledger-income-1x", state(LedgerType.CREDIT, true), income, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun income_2x() = ledger("ledger-income-2x", state(LedgerType.CREDIT, true), income, 2f)

    @Test fun empty_1x() = ledger("ledger-empty-1x", state(LedgerType.CREDIT, false), emptyList(), 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun empty_2x() = ledger("ledger-empty-2x", state(LedgerType.CREDIT, false), emptyList(), 2f)

    @Test fun bin_1x() = bin("bin-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun bin_2x() = bin("bin-2x", 2f)

    /** The bin most users see: nothing in it. */
    @Test
    fun binEmpty_1x() = rule.captureScreenGolden("bin-empty-1x", 1f) {
        LfTheme { BinScreen(state = BinUiState(isLoaded = true), onEvent = {}, onBack = {}) }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun binEmpty_2x() = rule.captureScreenGolden("bin-empty-2x", 2f) {
        LfTheme { BinScreen(state = BinUiState(isLoaded = true), onEvent = {}, onBack = {}) }
    }

    private companion object {
        /** 2026-08-19 as an epoch day; noon UTC that day is [NOW]. */
        const val TODAY = 20_684
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val NOW = TODAY * DAY + 12 * HOUR
        const val CATEGORY_BLUE = 0xFF3E6AD6.toInt()
        val DONE = LoadState.NotLoading(endOfPaginationReached = true)
    }
}
