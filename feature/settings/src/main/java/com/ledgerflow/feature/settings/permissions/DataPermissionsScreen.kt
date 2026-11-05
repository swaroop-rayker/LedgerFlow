package com.ledgerflow.feature.settings.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSettingsPage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.security.HeldPermission
import com.ledgerflow.core.domain.security.PermissionGrant
import com.ledgerflow.core.domain.security.PermissionKind
import com.ledgerflow.feature.settings.PanelRow

/** Android's name for each, or near it, so the user finds the same words in its settings. */
internal fun permissionTitle(kind: PermissionKind): String = when (kind) {
    PermissionKind.NOTIFICATION_ACCESS -> "Notification access"
    PermissionKind.RECEIVE_SMS -> "SMS"
    PermissionKind.CAMERA -> "Camera"
    PermissionKind.POST_NOTIFICATIONS -> "Notifications"
    PermissionKind.INTERNET -> "Internet"
    PermissionKind.BIOMETRIC -> "Fingerprint and face"
    PermissionKind.BACKGROUND_WORK -> "Background work"
    PermissionKind.UNEXPLAINED -> "Unexplained permission"
}

/**
 * Why LedgerFlow holds each: one line at 1.0, the page is scanned. The same
 * reasons as the privacy policy's (`docs/play/privacy-policy.md`,
 * Permissions), shorter; the two must not disagree.
 */
internal fun permissionWhy(kind: PermissionKind): String = when (kind) {
    PermissionKind.NOTIFICATION_ACCESS -> "Reads notifications from the payment apps you chose, and no others."
    PermissionKind.RECEIVE_SMS -> "Reads bank and card messages for your Inbox."
    PermissionKind.CAMERA -> "Scans receipts and recovery QR codes, on this phone."
    PermissionKind.POST_NOTIFICATIONS -> "Tells you when a payment waits or a budget nears its limit."
    PermissionKind.INTERNET -> "Brought in by Google's on-phone text reader. LedgerFlow does not use it."
    PermissionKind.BIOMETRIC -> "Used by App lock only, through your phone's prompt."
    PermissionKind.BACKGROUND_WORK -> "Processes captured messages and runs backups while closed."
    PermissionKind.UNEXPLAINED -> "LedgerFlow does not say why it holds this. Please report it."
}

/**
 * Data permissions (V2-PLAN §3.5; DESIGN_SYSTEM_PLAN §5.6): one row per
 * permission the install holds, what it is for and whether it is allowed.
 * The ones a user can take back open Android's own page for them, marked as
 * leaving the app; the ones Android grants at install sit in a second panel
 * with no tap, because there is nowhere to send the user.
 *
 * Nothing here changes a permission. Android's settings are where a grant is
 * given or taken, and the page says so rather than offering switches it
 * cannot honour.
 */
@Composable
public fun DataPermissionsScreen(
    state: DataPermissionsUiState,
    onOpen: (PermissionKind) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfSettingsPage(
        title = "Data permissions",
        subtitle = "What LedgerFlow may use on this phone, and why. Change them in Android's settings.",
        modifier = modifier,
        onBack = onBack,
    ) {
        if (state.revocable.isNotEmpty()) {
            item(key = "revocable", contentType = "section") {
                PermissionSection(heading = "You can turn these off", rows = state.revocable, onOpen = onOpen)
            }
        }
        if (state.atInstall.isNotEmpty()) {
            item(key = "at-install", contentType = "section") {
                PermissionSection(heading = "Granted when installed", rows = state.atInstall, onOpen = onOpen)
            }
        }
    }
}

/** A heading over one panel of rows; the page's two sections share the shape. */
@Composable
private fun PermissionSection(heading: String, rows: List<PermissionRow>, onOpen: (PermissionKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
        Text(text = heading, style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfPanel {
            rows.forEachIndexed { index, row ->
                if (index > 0) LfDivider()
                PermissionItem(row = row, onOpen = onOpen)
            }
        }
    }
}

/** Two lines, as every settings row: the name, then its state and its reason. */
@Composable
private fun PermissionItem(row: PermissionRow, onOpen: (PermissionKind) -> Unit) {
    PanelRow(onClick = if (row.opensSettings) ({ onOpen(row.kind) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
            ) {
                Text(text = row.title, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
                Text(text = row.sentence, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            }
            if (row.opensSettings) {
                // Decoration: the row's tap already says where it goes; the
                // mark says it is out of the app, unlike a chevron's page.
                Icon(
                    imageVector = LfIcons.OpensSystemSettings,
                    contentDescription = null,
                    tint = LfTheme.colors.text.muted,
                    modifier = Modifier.padding(start = LfTheme.space.itemGap),
                )
            }
        }
    }
}

/**
 * Android's page for [kind], best first: notification access has its own list
 * (with the app's notification page as the fallback some OEM builds need, as
 * onboarding does), notifications the app's notification page, and the
 * runtime permissions the app's own page, where Permissions is one tap away.
 * Every fallback ends at the app's page, which every build has.
 */
public fun permissionSettingsIntents(context: Context, kind: PermissionKind): List<Intent> {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
    val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val intents = when (kind) {
        PermissionKind.NOTIFICATION_ACCESS ->
            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appNotifications, appDetails)
        PermissionKind.POST_NOTIFICATIONS -> listOf(appNotifications, appDetails)
        PermissionKind.RECEIVE_SMS,
        PermissionKind.CAMERA,
        PermissionKind.INTERNET,
        PermissionKind.BIOMETRIC,
        PermissionKind.BACKGROUND_WORK,
        PermissionKind.UNEXPLAINED,
        -> listOf(appDetails)
    }
    return intents.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}

/** Opens the first of [permissionSettingsIntents] this phone has. A build with none loses a tap, not the app. */
public fun openPermissionSettings(context: Context, kind: PermissionKind) {
    for (intent in permissionSettingsIntents(context, kind)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun DataPermissionsPreview() {
    LfTheme {
        DataPermissionsScreen(
            state = DataPermissionsUiState(
                rows = permissionRows(
                    listOf(
                        HeldPermission(PermissionKind.NOTIFICATION_ACCESS, PermissionGrant.ALLOWED, emptyList()),
                        HeldPermission(PermissionKind.CAMERA, PermissionGrant.NOT_ALLOWED, emptyList()),
                        HeldPermission(PermissionKind.INTERNET, PermissionGrant.AT_INSTALL, emptyList()),
                    ),
                ),
            ),
            onOpen = {},
            onBack = {},
        )
    }
}
