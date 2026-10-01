package com.ledgerflow.feature.entry

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
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
 * The entry form at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * - **Fresh** — the form as the centre action opens it: empty, with the
 *   frequent combos offered and one unsaved draft waiting (ADR-0013's stack).
 * - **Filled** — amount, category, merchant and a note, the state Save is
 *   pressed from.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class EntryScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: EntryUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { EntryScreen(state = state, onEvent = {}, onDone = {}) }
        }

    private val food = Category("c1", null, LedgerType.DEBIT, "Food", "cart", 0x00FF8800, 1, true)
    private val groceries = Category("c2", "c1", LedgerType.DEBIT, "Groceries", "cart", 0x00FF8800, 2, false)
    private val bigBazaar = Merchant("m1", "Big Bazaar", "bigbazaar", "c1", null)

    private val fresh = EntryUiState(
        occurredAt = NOW,
        tree = listOf(CategoryTree(food, listOf(groceries))),
        merchants = listOf(bigBazaar),
        combos = listOf(
            EntryComboChip("Groceries · Big Bazaar", "c1", "c2", "m1", null),
            EntryComboChip("Food", "c1", null, null, null),
        ),
        unsaved = listOf(
            EntryDraftCard(
                id = "d1",
                amountMinor = 450_00,
                currencyCode = "INR",
                note = "Dinner with Asha",
                lineItemCount = 0,
                updatedAt = NOW - 3_600_000L,
                age = "1 hour ago",
                filedAs = "Food",
            ),
        ),
    )

    private val filled = EntryUiState(
        amountText = "1,240.50",
        amountMinor = 1_240_50L,
        categoryId = "c1",
        subcategoryId = "c2",
        merchantId = "m1",
        note = "Monthly staples",
        occurredAt = NOW,
        tree = listOf(CategoryTree(food, listOf(groceries))),
        merchants = listOf(bigBazaar),
    )

    @Test fun fresh_1x() = capture("entry-fresh-1x", fresh, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun fresh_2x() = capture("entry-fresh-2x", fresh, 2f)

    @Test fun filled_1x() = capture("entry-filled-1x", filled, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun filled_2x() = capture("entry-filled-2x", filled, 2f)

    private companion object {
        const val NOW = 1_787_130_000_000L
    }
}
