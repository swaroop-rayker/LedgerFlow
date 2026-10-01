package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
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
 * The Inbox and the review screen at font scale 1.0 and 2.0 (§12, P5 step 4),
 * each capture also running §9.6's touch-target and label checks.
 *
 * - **Queue** — a read SMS, a read notification and an unrecognised ("ghost")
 *   candidate, with counts on two other filters so the chip row is on screen.
 * - **Empty** — nothing waiting.
 * - **Review** — a parsed candidate, and §5.1's never-drop case where nothing
 *   was extracted and every field is the user's to fill.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class InboxScreensGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun inbox(name: String, state: InboxUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { InboxScreen(state = state, onEvent = {}, onReview = {}) }
        }

    private fun review(name: String, state: ReviewUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { ReviewScreen(state = state, onEvent = {}, onDone = {}, onBack = {}) }
        }

    private fun candidate(
        id: String,
        source: EntrySource,
        extracted: ExtractedTransaction,
        confidence: Double,
        needsManualFill: Boolean = false,
    ) = PendingTransaction(
        id = id,
        source = source,
        extracted = extracted,
        confidence = confidence,
        status = PendingStatus.PENDING,
        needsManualFill = needsManualFill,
        suppressedById = null,
        createdAt = NOW,
        reviewedAt = null,
        approvedEntryId = null,
    )

    private val queue = InboxUiState(
        rows = listOf(
            candidate(
                "1",
                EntrySource.SMS,
                ExtractedTransaction(
                    amount = Money(1_240_50),
                    currency = "INR",
                    direction = ExtractedDirection.DEBIT,
                    merchantRaw = "BIG BAZAAR",
                    accountLast4 = "6402",
                    confidence = 0.9,
                ),
                confidence = 0.9,
            ),
            candidate(
                "2",
                EntrySource.NOTIFICATION,
                ExtractedTransaction(
                    amount = Money(200_00),
                    currency = "INR",
                    direction = ExtractedDirection.DEBIT,
                    merchantRaw = "RAMESH KUMAR",
                    confidence = 0.4,
                ),
                confidence = 0.4,
            ),
            candidate("3", EntrySource.SMS, ExtractedTransaction(), confidence = 0.0, needsManualFill = true),
        ),
        pendingCount = 3,
        counts = mapOf(
            InboxFilter.PENDING to 3,
            InboxFilter.SUPPRESSED to 2,
            InboxFilter.DISCARDED to 1,
        ),
        loading = false,
    )

    private val categories = listOf(
        Category("c1", null, LedgerType.DEBIT, "Food", "cart", 0x00FF8800, 1, true),
        Category("c2", "c1", LedgerType.DEBIT, "Groceries", "cart", 0x00FF8800, 2, false),
    )

    private val parsed = ReviewUiState(
        pendingId = "1",
        loading = false,
        ledger = LedgerType.DEBIT,
        amountText = "1,240.50",
        rawMerchantName = "BIG BAZAAR",
        categories = categories,
        sourceLabel = "From an SMS",
        occurredAt = NOW,
        referenceHint = "Ref 999999999998",
    )

    private val unparsed = ReviewUiState(
        pendingId = "3",
        loading = false,
        ledger = null,
        bookIsUnread = true,
        needsManualFill = true,
        sourceLabel = "From an SMS",
        occurredAt = NOW,
    )

    @Test fun queue_1x() = inbox("inbox-queue-1x", queue, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun queue_2x() = inbox("inbox-queue-2x", queue, 2f)

    @Test fun empty_1x() = inbox("inbox-empty-1x", InboxUiState(loading = false), 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun empty_2x() = inbox("inbox-empty-2x", InboxUiState(loading = false), 2f)

    @Test fun reviewParsed_1x() = review("review-parsed-1x", parsed, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun reviewParsed_2x() = review("review-parsed-2x", parsed, 2f)

    @Test fun reviewUnparsed_1x() = review("review-unparsed-1x", unparsed, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun reviewUnparsed_2x() = review("review-unparsed-2x", unparsed, 2f)

    private companion object {
        /** 2026-08-19, mid-morning UTC. */
        const val NOW = 1_787_130_000_000L
    }
}
