package com.ledgerflow.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ledgerflow.core.designsystem.component.LfCaptureGuide
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §12's screenshot gate over the viewfinder guide (SPEC.md §5.3).
 *
 * ## The bug this exists to catch, which it was written after
 *
 * The guide started life inside `:feature:ocr`, where nothing renders a pixel
 * in CI, and it shipped **two** theme faults at once — only one of which was
 * visible on the device being tested:
 *
 * - a scrim built from the *active* palette **lightens** the surround in light
 *   theme, because `surfaceBase` is `#F7F8FA`
 * - corner marks built from `onAccent` come out near-black in dark theme
 *   (`#0B1020`), invisible against exactly the dark preview they mark
 *
 * Both are pixel failures with no assertion that could have caught them, which
 * is the case `CLAUDE.md` says to put a Canvas primitive in this module for.
 *
 * ## Rendered over a mid-grey, deliberately
 *
 * A camera preview is neither the app's surface nor a fixed colour, and a
 * guide drawn over *transparent* would make both faults invisible — the scrim
 * would have nothing to darken and the marks nothing to contrast against. Grey
 * stands in for an ordinary scene: light enough that a lightening scrim shows
 * as wrong, dark enough that near-black marks disappear.
 *
 * **Both themes, and they must be identical.** That is the assertion the
 * goldens encode: this paints over a camera image, which has no theme, so the
 * two renderings are the same pixels. A diff between them is the fault.
 *
 * No font-scale pair here, unlike the component goldens: the guide has no
 * text, so scale cannot move it. The hint beside it is ordinary `Text` and is
 * covered by the screen's own previews.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GUIDE_ROBOLECTRIC_SDK])
class LfCaptureGuideScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, darkTheme: Boolean) {
        composeRule.setContent {
            LfTheme(darkTheme = darkTheme) {
                Box(
                    modifier = Modifier
                        .width(PREVIEW_WIDTH.dp)
                        .aspectRatio(PREVIEW_ASPECT)
                        // Stands in for a camera frame. See the class KDoc.
                        .background(Color(SCENE_GREY)),
                ) {
                    LfCaptureGuide(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(PREVIEW_ASPECT),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$GUIDE_GOLDEN_DIR/$name.png", roborazziOptions = LfScreenshotOptions)
    }

    @Test
    fun captureGuide_light() = capture("capture-guide-light", darkTheme = false)

    @Test
    fun captureGuide_dark() = capture("capture-guide-dark", darkTheme = true)
}

private const val PREVIEW_WIDTH = 280
private const val PREVIEW_ASPECT = 3f / 4f
private const val SCENE_GREY = 0xFF8A8A8AL.toInt()
private const val GUIDE_GOLDEN_DIR = "src/test/screenshots"
private const val GUIDE_ROBOLECTRIC_SDK = 34
