package com.ledgerflow.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNavRow
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfTabPage
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The fourth tab (design-system step D4, DESIGN_SYSTEM_PLAN Q8): it replaced
 * Settings in the bar. Its rows are [ProfilePages], then Settings.
 *
 * **Organise and Data live here, not under Settings** (owner, 2026-10-27,
 * BUG65). They are the user's own things -- their categories, their budgets,
 * their backups and exports -- where the rest of Settings is how the app
 * behaves. D4 first shipped an "Export data" row here *and* Export under
 * Settings > Data, two rows for one screen; now every page has exactly one row
 * that opens it, which `Bug65_EveryPageHasOneRowTest` holds.
 *
 * **No profile header yet** (owner, 2026-10-27). The reference samples put a
 * name and photo at the top, but nothing can store either until V2-PLAN §3.1
 * adds them, and a header that invites a tap and does nothing is the dead
 * control this app does not ship. It arrives with §3.1. So do the Account and
 * "Sign out of Google Drive" rows, with their own V2 phases.
 */
@Composable
public fun ProfileScreen(
    onPage: (SettingsPage) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No scaffold of its own: the shell hosts the tabs.
    LfTabPage(title = "Profile", modifier = modifier) {
        item(key = "rows", contentType = "panel") {
            LfPanel {
                SettingsSections.filter { it.page in ProfilePages }.forEach { section ->
                    LfNavRow(title = section.heading, onClick = { onPage(section.page) })
                    LfDivider()
                }
                LfNavRow(title = "Settings", onClick = onSettings)
            }
        }
    }
}

/** The pages Profile opens directly; the Settings index lists the rest. */
internal val ProfilePages: Set<SettingsPage> = setOf(SettingsPage.Organise, SettingsPage.Data)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ProfilePreview() {
    LfTheme { ProfileScreen(onPage = {}, onSettings = {}) }
}
