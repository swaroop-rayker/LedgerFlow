package com.ledgerflow.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNavRow
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSettingsPage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.security.SecurityPreferences

/**
 * Settings: one row per section, each opening its page (design-system step D4).
 *
 * Reached from Profile. The rows are [SettingsSections] in order, so a section
 * added there is a row here with nothing else to wire. They sit in one panel,
 * as a list of places to go should: scanned, not read.
 */
@Composable
public fun SettingsIndexScreen(
    state: SettingsUiState,
    onPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfSettingsPage(
        title = "Settings",
        modifier = modifier,
        actions = { LfButton(text = "Back", onClick = onBack, style = LfButtonStyle.Inline) },
    ) {
        item(key = "pages", contentType = "panel") {
            LfPanel {
                SettingsSections.forEachIndexed { index, section ->
                    if (index > 0) LfDivider()
                    LfNavRow(
                        title = section.heading,
                        value = indexValueFor(section.page, state),
                        onClick = { onPage(section.page) },
                    )
                }
            }
        }
    }
}

/**
 * What a section's row says beside its name, when one value says it (owner,
 * 2026-10-27): whether capture is on, which lock guards the app, the theme and
 * the base currency. Organise, Data and Tools hold several equal things, so
 * they show none rather than pick one to promote.
 *
 * Capture shows no value while it is reconnecting -- that is not news -- or
 * where it cannot run at all; the page's own sentence covers both.
 */
internal fun indexValueFor(page: SettingsPage, state: SettingsUiState): String? = when (page) {
    SettingsPage.Notifications -> when (state.captureHealth) {
        NotificationCaptureHealth.CONNECTED -> "On"
        NotificationCaptureHealth.NOT_GRANTED -> "Off"
        NotificationCaptureHealth.DEAD -> "Stopped"
        NotificationCaptureHealth.RECONNECTING,
        NotificationCaptureHealth.UNAVAILABLE,
        -> null
    }
    // The app lock is the phone's own screen lock (SPEC §7.6). A LedgerFlow
    // PIN would be a second answer here (DESIGN_SYSTEM_PLAN Q7).
    SettingsPage.Security -> if (state.security.appLock) "Phone's lock" else "Off"
    SettingsPage.Appearance -> state.appearance.theme.label
    SettingsPage.Region -> state.baseCurrency
    SettingsPage.Organise,
    SettingsPage.Data,
    SettingsPage.Tools,
    -> null
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun SettingsIndexPreview() {
    LfTheme {
        SettingsIndexScreen(
            state = SettingsUiState(
                isLoaded = true,
                captureHealth = NotificationCaptureHealth.CONNECTED,
                appearance = AppearancePreferences(theme = ThemePreference.DARK),
                baseCurrency = "INR",
                security = SecurityPreferences(appLock = true),
            ),
            onPage = {},
            onBack = {},
        )
    }
}
