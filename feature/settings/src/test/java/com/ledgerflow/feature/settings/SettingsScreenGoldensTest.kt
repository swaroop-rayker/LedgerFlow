package com.ledgerflow.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.format.DisplayConventions
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings tab's goldens at 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * They were More's (`more-*`) until V2-2 moved every row here under section
 * headings; the rows and their sentences are unchanged.
 *
 * Two states, chosen for what the rows *say*: a lived-in install (capture on,
 * a nightly backup, things in the bin and receipts kept) and a new one (capture
 * off, never backed up, nothing anywhere) — every subtitle has a different
 * sentence in each, and those sentences are the screen.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = SETTINGS_1X)
open class SettingsScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The process-wide conventions are set to the state's own for the capture
     * (V2-3): in the app the two are always the same store, and a golden whose
     * backup date ignored the state's date order would record a screen that
     * cannot happen.
     */
    private fun capture(
        name: String,
        state: SettingsUiState,
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val host = DisplayConventions.current
        DisplayConventions.current = state.region
        try {
            rule.captureScreenGolden(name, fontScale, layoutDirection) {
                // In LfScaffold, as the shell hosts every tab: the screen paints
                // no background of its own.
                LfTheme {
                    LfScaffold {
                        SettingsScreen(
                            state = state,
                            onCategories = {},
                            onBudgets = {},
                            onExport = {},
                            onDeletedEntries = {},
                            onNotificationAccess = {},
                            onBackUp = {},
                            onDiagnostics = {},
                            onBudgetAlerts = {},
                            onPaymentApps = {},
                            onEvent = {},
                        )
                    }
                }
            }
        } finally {
            DisplayConventions.current = host
        }
    }

    private val livedIn = SettingsUiState(
        deletedCount = 3,
        isLoaded = true,
        receipts = AttachmentUsage(count = 12, bytes = 3_400_000L),
        captureHealth = NotificationCaptureHealth.CONNECTED,
        lastBackupAt = 1_790_000_000_000L,
        nightlyBackupsEnabled = true,
        // Not the defaults, so the golden shows a selection that moved (V2-3).
        appearance = AppearancePreferences(
            theme = ThemePreference.DARK,
            animations = AnimationPreference.REDUCED,
            startScreen = StartScreen.LEDGER,
        ),
        baseCurrency = "INR",
        // Currency & region, also moved from the defaults.
        region = RegionConventions(grouping = NumberGrouping.WESTERN, dateOrder = DateOrder.DAY_MONTH_YEAR),
        budgetAlerts = AlertChannelState.ON,
        paymentApps = 20,
    )

    private val fresh = SettingsUiState(
        deletedCount = 0,
        isLoaded = true,
        captureHealth = NotificationCaptureHealth.NOT_GRANTED,
        baseCurrency = "INR",
        budgetAlerts = AlertChannelState.APP_OFF,
        paymentApps = 20,
    )

    @Test fun livedIn_1x() = capture("settings-lived-in-1x", livedIn, 1f)

    @Config(qualifiers = SETTINGS_2X)
    @Test fun livedIn_2x() = capture("settings-lived-in-2x", livedIn, 2f)

    @Test fun fresh_1x() = capture("settings-fresh-1x", fresh, 1f)

    @Config(qualifiers = SETTINGS_2X)
    @Test fun fresh_2x() = capture("settings-fresh-2x", fresh, 2f)

    /**
     * Right-to-left (BUG61): headings and rows on the right, the segmented
     * controls mirrored so the first option is rightmost, every English line
     * still LTR with its full stop last.
     */
    @Test fun livedIn_rtl_1x() = capture("settings-lived-in-rtl-1x", livedIn, 1f, LayoutDirection.Rtl)
}

/**
 * Taller than the shared phone canvas: V2-3's Appearance and Currency & region
 * sections took the tab past 1800 dp, and Security & privacy past 2400, and a
 * golden of the top of a screen reviews only the top (`ScreenGoldens`). Same
 * 360 dp width.
 */
private const val SETTINGS_1X = "w360dp-h2800dp"
private const val SETTINGS_2X = "w360dp-h5600dp"

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenGoldensDarkTest : SettingsScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
