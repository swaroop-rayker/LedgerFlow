package com.ledgerflow.feature.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
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
 * Home's goldens at font scale 1.0 and 2.0 (§12, P5 step 4), each capture also
 * running §9.6's touch-target and label checks.
 *
 * Home is the banners and an empty state until v2's Home v1 (`docs/V2-PLAN.md`
 * BUG-C), so these are its real states today: nothing to say, and both banners
 * at once — the tallest it gets, and the case where two actions compete.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class DashboardScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: DashboardUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { DashboardScreen(state = state, onSetUpNotifications = {}, onBackUpNow = {}) }
        }

    private val quiet = DashboardUiState(captureHealth = NotificationCaptureHealth.CONNECTED)

    private val bothBanners = DashboardUiState(
        captureHealth = NotificationCaptureHealth.NOT_GRANTED,
        backupReminder = BackupReminder.Stale(daysAgo = 12),
    )

    @Test fun quiet_1x() = capture("home-quiet-1x", quiet, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun quiet_2x() = capture("home-quiet-2x", quiet, 2f)

    @Test fun bothBanners_1x() = capture("home-banners-1x", bothBanners, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun bothBanners_2x() = capture("home-banners-2x", bothBanners, 2f)
}
