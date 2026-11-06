package com.ledgerflow.feature.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.statement.BookShare
import com.ledgerflow.core.domain.statement.ImportBatch
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Approve all" for an import (V2-4, D8): the panel above the queue, at 1x,
 * 2x and from the right, and its confirmation naming the count and each
 * book's total.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class InboxImportGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private val batch = ImportBatch(
        fingerprint = "fp",
        fileName = "Example-Bank-Statement-Oct-2026.csv",
        importedAt = NOW,
        baseCurrency = "INR",
        spent = BookShare(10, Money(1_722_690)),
        received = BookShare(4, Money(8_712_400)),
        toReview = 2,
    )

    private fun row(id: String, minor: Long, direction: ExtractedDirection, text: String) = PendingTransaction(
        id = id,
        source = EntrySource.IMPORT,
        extracted = ExtractedTransaction(
            amount = Money(minor),
            currency = "INR",
            direction = direction,
            merchantRaw = text,
            occurredAt = NOW,
            confidence = 1.0,
        ),
        confidence = 1.0,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = NOW,
        reviewedAt = null,
        approvedEntryId = null,
    )

    private val queue = InboxUiState(
        rows = listOf(
            row("1", 41_000, ExtractedDirection.DEBIT, "UPI-SWIGGY-SWIGGY@AXISBANK-628812345678-ORDER"),
            row("2", 8_500_000, ExtractedDirection.CREDIT, "NEFT CR-ACME LTD-SALARY OCT 2026"),
        ),
        pendingCount = 16,
        counts = mapOf(InboxFilter.PENDING to 16, InboxFilter.SUPPRESSED to 2),
        loading = false,
        importBatches = listOf(batch),
    )

    private fun inbox(name: String, fontScale: Float, direction: LayoutDirection = LayoutDirection.Ltr) =
        rule.captureScreenGolden(name, fontScale, direction) {
            LfTheme { InboxScreen(state = queue, onEvent = {}, onReview = {}) }
        }

    @Test fun panel_1x() = inbox("inbox-import-panel-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun panel_2x() = inbox("inbox-import-panel-2x", 2f)

    @Test fun panelRtl_1x() = inbox("inbox-import-panel-rtl-1x", 1f, LayoutDirection.Rtl)

    @Test fun confirm_1x() = withDialog("inbox-import-confirm-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun confirm_2x() = withDialog("inbox-import-confirm-2x", 2f)

    /**
     * A dialog is its own window: it ignores `captureScreenGolden`'s density
     * override, so the font scale goes through the configuration and the
     * whole screen is captured, as `SettingsChoiceDialogGoldensTest` does.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun withDialog(name: String, fontScale: Float) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        // The rows show times: UTC, as `captureScreenGolden` pins it, or this
        // golden would differ between a Windows box in IST and a Linux runner.
        val hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            captureDialog(name)
        } finally {
            TimeZone.setDefault(hostZone)
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDialog(name: String) {
        rule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    LfScaffold {
                        InboxScreen(
                            state = queue.copy(confirmation = InboxConfirmation.ApproveImport(batch)),
                            onEvent = {},
                            onReview = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    private companion object {
        /** 5 Nov 2026, mid-morning UTC. */
        const val NOW = 1_793_870_000_000L
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxImportGoldensDarkTest : InboxImportGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
