package com.ledgerflow.feature.ocr.capture

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
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
 * Receipt capture at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * Both states have the camera **denied**, as the screen's own previews do: a
 * granted camera binds CameraX, which Robolectric cannot host, and the camera
 * view is a live image with nothing of the app's own to diff. What is the
 * app's own is here — the way forward without a camera (import a file), and
 * what a recognised bill looks like before it is saved.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class OcrCaptureScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: OcrCaptureUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { OcrCaptureScreen(state = state, onEvent = {}, onBack = {}) }
        }

    private val denied = OcrCaptureUiState(cameraPermission = CameraPermission.Denied)

    private val recognised = OcrCaptureUiState(
        cameraPermission = CameraPermission.Denied,
        result = RecognitionSummary(
            elementCount = 47,
            rawPreview = "LOCAL KIRANA RICE 5KG 420.00 TOMATO 20.00 TOTAL 473.00",
            sourceLabel = "Imported",
            merchant = "LOCAL KIRANA",
            totalText = "₹473.00",
            items = listOf(
                ExtractedItemRow("RICE 5KG", "₹420.00"),
                ExtractedItemRow("TOMATO LOCAL GRADE A 1KG", "₹20.00"),
                ExtractedItemRow("TOOR DAL 1KG", "₹33.00"),
            ),
            balance = "Balanced",
        ),
    )

    @Test fun denied_1x() = capture("scan-denied-1x", denied, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun denied_2x() = capture("scan-denied-2x", denied, 2f)

    @Test fun recognised_1x() = capture("scan-recognised-1x", recognised, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun recognised_2x() = capture("scan-recognised-2x", recognised, 2f)
}
