package com.ledgerflow.feature.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.LayoutDirection
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's goldens at font scale 1.0 and 2.0 (§12, P5 step 4), each capture also
 * running §9.6's touch-target and label checks.
 *
 * The banner goldens are an **empty vault** (both books answered, neither has
 * an entry), which is what they showed before Home v1 and still show: the
 * banners above "Nothing here yet". Home v1 (`docs/V2-PLAN.md` BUG-C) adds a
 * full month, every card at once and the tallest Home gets, and the frame
 * before the figures land.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class DashboardScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        state: DashboardUiState,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) =
        rule.captureScreenGolden(name, fontScale, direction) {
            // In LfScaffold, as the shell hosts every tab: the screen paints no
            // background of its own.
            LfTheme { LfScaffold { DashboardScreen(state = state, onSetUpNotifications = {}, onBackUpNow = {}) } }
        }

    /** Both books read, both empty: what every pre-v2 golden here depicted. */
    private val emptyVault = DashboardUiState(recentSpending = emptyList(), recentIncome = emptyList())

    private val quiet = emptyVault.copy(captureHealth = NotificationCaptureHealth.CONNECTED)

    private val bothBanners = emptyVault.copy(
        captureHealth = NotificationCaptureHealth.NOT_GRANTED,
        backupReminder = BackupReminder.Stale(daysAgo = 12),
    )

    @Test fun quiet_1x() = capture("home-quiet-1x", quiet, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun quiet_2x() = capture("home-quiet-2x", quiet, 2f)

    @Test fun bothBanners_1x() = capture("home-banners-1x", bothBanners, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun bothBanners_2x() = capture("home-banners-2x", bothBanners, 2f)

    /**
     * §16 Q16's card (BUG50), above the others: 2 Oct 04:44 to 3 Oct 02:32 UTC,
     * the harness's zone. Shown with a backup reminder so the stacking is seen.
     */
    private val captureGap = emptyVault.copy(
        captureHealth = NotificationCaptureHealth.CONNECTED,
        backupReminder = BackupReminder.Stale(daysAgo = 12),
        captureGap = CaptureGap(firstMissedAt = 1_790_916_240_000L, lastMissedAt = 1_790_994_720_000L, missed = 7),
    )

    @Test fun captureGap_1x() = capture("home-capture-gap-1x", captureGap, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun captureGap_2x() = capture("home-capture-gap-2x", captureGap, 2f)

    /** BUG7(c)'s offer after two crashes, the latest 2 Oct 04:44 UTC. */
    private val crashReports = emptyVault.copy(
        captureHealth = NotificationCaptureHealth.CONNECTED,
        crashReports = CrashSummary(count = 2, latestAt = 1_790_916_240_000L),
    )

    @Test fun crashReports_1x() = capture("home-crash-reports-1x", crashReports, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun crashReports_2x() = capture("home-crash-reports-2x", crashReports, 2f)

    // ── Home v1 (V2-PLAN BUG-C) ─────────────────────────────────────────────

    /**
     * Every card at once, 16 October: three to review, both books' figures
     * against 1–16 September, three budgets, two charges due, coverage, and
     * both recent lists. The tallest Home gets, so the 2.0 capture is where a
     * clipped figure or a wrapped control label would show.
     */
    @Test fun month_1x() = capture("home-month-1x", HomeFixtures.populated, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun month_2x() = capture("home-month-2x", HomeFixtures.populated, 2f)

    /**
     * The same month on an Arabic or Hebrew phone (BUG61): mirrored, every
     * English line on the right with its full stop last, each "-" in front of
     * its amount, the comparisons on the screen's end (left).
     */
    @Test fun monthRtl_1x() = capture("home-month-rtl-1x", HomeFixtures.populated, 1f, LayoutDirection.Rtl)

    @Config(qualifiers = PHONE_2X)
    @Test fun monthRtl_2x() = capture("home-month-rtl-2x", HomeFixtures.populated, 2f, LayoutDirection.Rtl)

    /**
     * The frame before the figures land: the books have answered, the summary
     * has not. The month card is at its full height with placeholders, so
     * nothing below it moves when the numbers arrive.
     */
    private val figuresLoading = HomeFixtures.populated.copy(summary = null, pendingCount = 0)

    @Test fun loading_1x() = capture("home-loading-1x", figuresLoading, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun loading_2x() = capture("home-loading-2x", figuresLoading, 2f)
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenGoldensDarkTest : DashboardScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
