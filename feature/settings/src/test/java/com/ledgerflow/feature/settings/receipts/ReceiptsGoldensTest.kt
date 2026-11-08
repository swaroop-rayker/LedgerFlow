package com.ledgerflow.feature.settings.receipts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Receipts page and one receipt (V2-5, owner's layout A): 1x, 2x, from the
 * right, selecting, empty, the delete Warning, and the receipt with its image,
 * without one, and still in the Inbox.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class ReceiptsGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun page(
        name: String,
        fontScale: Float,
        state: ReceiptsUiState = previewReceiptsState,
        rows: List<ReceiptListItem> = previewReceipts,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        LfTheme {
            LfScaffold {
                ReceiptsScreen(
                    state = state,
                    // Finished load states, stated: a static PagingData without
                    // them reports Loading forever, and the empty state waits for one.
                    receipts = flowOf(PagingData.from(rows, sourceLoadStates = LoadStates(DONE, DONE, DONE))),
                    onEvent = {},
                    onOpen = {},
                    onBack = {},
                )
            }
        }
    }

    @Test fun page_1x() = page("receipts-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun page_2x() = page("receipts-2x", 2f)

    @Test fun page_1x_rtl() = page("receipts-1x-rtl", 1f, direction = LayoutDirection.Rtl)

    @Test fun selecting_1x() = page(
        "receipts-selecting-1x",
        1f,
        state = previewReceiptsState.copy(selecting = true, selected = setOf("r1", "r2"), selectedBytes = 680_000),
    )

    @Config(qualifiers = PHONE_2X)
    @Test fun selecting_2x() = page(
        "receipts-selecting-2x",
        2f,
        state = previewReceiptsState.copy(selecting = true, selected = setOf("r1", "r2"), selectedBytes = 680_000),
    )

    /** A period with none: the way to older receipts is offered. */
    @Test fun empty_1x() = page(
        "receipts-empty-1x",
        1f,
        state = previewReceiptsState.copy(period = ReceiptPeriod.Today, count = null),
        rows = emptyList(),
    )

    @Test fun deleteDialog_1x() = withDialog("receipts-delete-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun deleteDialog_2x() = withDialog("receipts-delete-2x", 2f)

    private fun receipt(
        name: String,
        fontScale: Float,
        state: ReceiptUiState,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) =
        rule.captureScreenGolden(name, fontScale, direction) {
            LfTheme {
                LfScaffold {
                    ReceiptScreen(state = state, onEvent = {}, onReviewInInbox = {}, onBack = {})
                }
            }
        }

    private val copies = ReceiptCopies(backupFolder = "LedgerFlow", drive = true)

    @Test fun receipt_1x() = receipt(
        "receipt-1x",
        1f,
        ReceiptUiState(receipt = previewReceipts[0], image = ReceiptPicture.Shown(fakeReceipt()), copies = copies),
    )

    @Config(qualifiers = PHONE_2X)
    @Test fun receipt_2x() = receipt(
        "receipt-2x",
        2f,
        ReceiptUiState(receipt = previewReceipts[0], image = ReceiptPicture.Shown(fakeReceipt()), copies = copies),
    )

    @Test fun receipt_1x_rtl() = receipt(
        "receipt-1x-rtl",
        1f,
        ReceiptUiState(receipt = previewReceipts[0], image = ReceiptPicture.Shown(fakeReceipt()), copies = copies),
        LayoutDirection.Rtl,
    )

    @Test fun receiptUnavailable_1x() = receipt(
        "receipt-unavailable-1x",
        1f,
        ReceiptUiState(receipt = previewReceipts[1], image = ReceiptPicture.Unavailable, copies = copies),
    )

    @Test fun receiptInInbox_1x() = receipt(
        "receipt-inbox-1x",
        1f,
        ReceiptUiState(receipt = previewReceipts[2], image = ReceiptPicture.Shown(fakeReceipt()), copies = copies),
    )

    /**
     * A receipt-shaped bitmap: pale paper with grey bars for lines. No text, so
     * the golden does not depend on a font the image would have been rendered in.
     */
    private fun fakeReceipt() = Bitmap.createBitmap(RECEIPT_W, RECEIPT_H, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(237, 235, 230))
        val ink = Paint().apply { color = Color.rgb(90, 90, 90) }
        repeat(LINES) { line ->
            val top = MARGIN + line * LINE_PITCH
            val width = if (line % 3 == 0) RECEIPT_W - 2 * MARGIN else (RECEIPT_W - 2 * MARGIN) * 2 / 3
            val left = MARGIN.toFloat()
            canvas.drawRect(left, top.toFloat(), left + width, (top + LINE_HEIGHT).toFloat(), ink)
        }
    }.asImageBitmap()

    /**
     * A dialog is its own window: it ignores `captureScreenGolden`'s density
     * override, so the font scale goes through the configuration and the whole
     * screen is captured, with the zone pinned to UTC as `InboxImportGoldensTest` does.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun withDialog(name: String, fontScale: Float) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        val hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            rule.setContent {
                GoldenTheme.Provide {
                    LfTheme {
                        LfScaffold {
                            ReceiptsContent(
                                state = previewReceiptsState.copy(
                                    selecting = true,
                                    selected = setOf("r1", "r2"),
                                    selectedBytes = 680_000,
                                    confirmation = ReceiptsConfirmation.DeleteSelected(count = 2, bytes = 680_000),
                                ),
                                items = flowOf(
                                    PagingData.from(previewReceipts, sourceLoadStates = LoadStates(DONE, DONE, DONE)),
                                ).collectAsLazyPagingItems(),
                                onEvent = {},
                                onOpen = {},
                                onBack = {},
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
        } finally {
            TimeZone.setDefault(hostZone)
        }
    }

    private companion object {
        val DONE = LoadState.NotLoading(endOfPaginationReached = true)
        const val RECEIPT_W = 480
        const val RECEIPT_H = 720
        const val MARGIN = 40
        const val LINES = 18
        const val LINE_PITCH = 36
        const val LINE_HEIGHT = 14
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptsGoldensDarkTest : ReceiptsGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
