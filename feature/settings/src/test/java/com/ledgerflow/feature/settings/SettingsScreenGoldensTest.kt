package com.ledgerflow.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.format.DisplayConventions
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
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
 * Profile, the Settings index and every Settings page, at 1.0 and 2.0 (§12),
 * with §9.6's checks.
 *
 * Until design-system step D4 these were one tall golden of the Settings tab
 * (`settings-lived-in-*`, `settings-fresh-*`), and More's before V2-2. The rows
 * and their sentences are unchanged; each section is now its own page.
 *
 * Two states, chosen for what the rows *say*: a lived-in install (capture on,
 * a nightly backup, things in the bin and receipts kept) and a new one (capture
 * off, never backed up, nothing anywhere). The fresh state is captured only
 * where its sentences differ: the index, Notifications and Data.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
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
        screen: @Composable () -> Unit,
    ) {
        val host = DisplayConventions.current
        DisplayConventions.current = state.region
        try {
            rule.captureScreenGolden(name, fontScale, layoutDirection) {
                // In LfScaffold, as the shell hosts every screen.
                LfTheme { LfScaffold { screen() } }
            }
        } finally {
            DisplayConventions.current = host
        }
    }

    private fun page(
        page: SettingsPage,
        state: SettingsUiState,
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val name = "settings-${page.name.lowercase()}-${if (state === fresh) "fresh" else "lived-in"}"
        val suffix = (if (layoutDirection == LayoutDirection.Rtl) "-rtl" else "") + if (fontScale > 1f) "-2x" else "-1x"
        capture(name + suffix, state, fontScale, layoutDirection) {
            SettingsSectionScreen(
                page = page,
                state = state,
                onBack = {},
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

    private fun index(name: String, state: SettingsUiState, fontScale: Float, direction: LayoutDirection) =
        capture(name, state, fontScale, direction) { SettingsIndexScreen(state = state, onPage = {}, onBack = {}) }

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
        security = SecurityPreferences(appLock = true),
    )

    private val fresh = SettingsUiState(
        deletedCount = 0,
        isLoaded = true,
        captureHealth = NotificationCaptureHealth.NOT_GRANTED,
        baseCurrency = "INR",
        budgetAlerts = AlertChannelState.APP_OFF,
        paymentApps = 20,
    )

    // Profile: no header yet (owner, 2026-10-27), two rows.
    @Test fun profile_1x() = capture("profile-1x", livedIn, 1f) { ProfileScreen(onSettings = {}, onExport = {}) }

    @Config(qualifiers = PHONE_2X)
    @Test fun profile_2x() = capture("profile-2x", livedIn, 2f) { ProfileScreen(onSettings = {}, onExport = {}) }

    @Test fun profile_rtl_1x() =
        capture("profile-rtl-1x", livedIn, 1f, LayoutDirection.Rtl) { ProfileScreen(onSettings = {}, onExport = {}) }

    // The index: each section's current value, where one value says it.
    @Test fun index_livedIn_1x() = index("settings-index-lived-in-1x", livedIn, 1f, LayoutDirection.Ltr)

    @Config(qualifiers = PHONE_2X)
    @Test fun index_livedIn_2x() = index("settings-index-lived-in-2x", livedIn, 2f, LayoutDirection.Ltr)

    @Test fun index_fresh_1x() = index("settings-index-fresh-1x", fresh, 1f, LayoutDirection.Ltr)

    /** Values on the left, chevrons pointing left (BUG61's direction rules). */
    @Test fun index_rtl_1x() = index("settings-index-lived-in-rtl-1x", livedIn, 1f, LayoutDirection.Rtl)

    @Test fun notifications_1x() = page(SettingsPage.Notifications, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun notifications_2x() = page(SettingsPage.Notifications, livedIn, 2f)

    @Test fun notifications_fresh_1x() = page(SettingsPage.Notifications, fresh, 1f)

    @Test fun organise_1x() = page(SettingsPage.Organise, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun organise_2x() = page(SettingsPage.Organise, livedIn, 2f)

    @Test fun data_1x() = page(SettingsPage.Data, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun data_2x() = page(SettingsPage.Data, livedIn, 2f)

    @Test fun data_fresh_1x() = page(SettingsPage.Data, fresh, 1f)

    @Test fun security_1x() = page(SettingsPage.Security, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun security_2x() = page(SettingsPage.Security, livedIn, 2f)

    @Test fun appearance_1x() = page(SettingsPage.Appearance, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun appearance_2x() = page(SettingsPage.Appearance, livedIn, 2f)

    /**
     * Right-to-left (BUG61): rows on the right, the segmented controls mirrored
     * so the first option is rightmost, every English line still LTR with its
     * full stop last.
     */
    @Test fun appearance_rtl_1x() = page(SettingsPage.Appearance, livedIn, 1f, LayoutDirection.Rtl)

    @Test fun region_1x() = page(SettingsPage.Region, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun region_2x() = page(SettingsPage.Region, livedIn, 2f)

    @Test fun tools_1x() = page(SettingsPage.Tools, livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun tools_2x() = page(SettingsPage.Tools, livedIn, 2f)
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenGoldensDarkTest : SettingsScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
