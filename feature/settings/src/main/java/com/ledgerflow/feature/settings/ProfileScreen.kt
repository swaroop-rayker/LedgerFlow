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
 * Settings in the bar, and Settings is its first row.
 *
 * **No profile header yet** (owner, 2026-10-27). The reference samples put a
 * name and photo at the top, but nothing can store either until V2-PLAN §3.1
 * adds them, and a header that invites a tap and does nothing is the dead
 * control this app does not ship. It arrives with §3.1. So do the Account and
 * "Sign out of Google Drive" rows, with their own V2 phases.
 *
 * Export is here as well as under Settings > Data: it is the one thing a
 * person reaching for "my data" looks for first, and the samples put it here.
 * Both rows open the same screen.
 */
@Composable
public fun ProfileScreen(
    onSettings: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No scaffold of its own: the shell hosts the tabs.
    LfTabPage(title = "Profile", modifier = modifier) {
        item(key = "rows", contentType = "panel") {
            LfPanel {
                LfNavRow(title = "Settings", onClick = onSettings)
                LfDivider()
                LfNavRow(title = "Export data", onClick = onExport)
            }
        }
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ProfilePreview() {
    LfTheme { ProfileScreen(onSettings = {}, onExport = {}) }
}
