package com.ledgerflow.feature.ocr.capture

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG74: once the camera was refused, Scan said so and offered no way to turn
 * it back on. Android stops asking after a refusal, so it could only be done by
 * someone who knew to find the app in the phone's settings. "Allow in Settings"
 * opens that page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug74_RefusedCameraCanBeAllowedTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aRefusedCamera_opensThisAppsSettings() {
        rule.setContent {
            LfTheme {
                OcrCaptureScreen(
                    state = OcrCaptureUiState(cameraPermission = CameraPermission.Denied),
                    onEvent = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Allow in Settings").performClick()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val started = generateSequence { shadowOf(app).nextStartedActivity }
            .firstOrNull { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
        assertThat(started).isNotNull()
        assertThat(started?.data?.schemeSpecificPart).isEqualTo(app.packageName)
    }
}
