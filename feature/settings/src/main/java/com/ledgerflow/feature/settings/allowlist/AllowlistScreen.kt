package com.ledgerflow.feature.settings.allowlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSwitchRow
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.InstalledApp

/**
 * Settings > Notifications > Payment apps: which apps LedgerFlow reads
 * notifications from (SPEC §5.2, D-10; `docs/V2-PLAN.md` §3.4, owner
 * 2026-10-24).
 *
 * The list is the privacy guarantee made visible: nothing from an app that is
 * not on it is read, logged or kept, because the filter runs before any body is
 * touched. So the screen says that first, and shows names and package names
 * only -- there is no content it could show for an app off the list.
 *
 * Built-in apps switch off and on, never go: switched off they stay listed, so
 * the way back is on the same screen. Apps the user added can be removed.
 * Adding an app that carries personal messages (SMS, chat, email) asks first,
 * because SPEC §3.1 is what happens next: every message it shows is read, kept
 * for 90 days, and queued in the Inbox unless it is a payment.
 */
@Composable
public fun AllowlistScreen(
    state: AllowlistUiState,
    onEvent: (AllowlistEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.confirming?.let { app -> PersonalMessagesWarning(app = app, onEvent = onEvent) }
    BackHandler(enabled = state.picking) { onEvent(AllowlistEvent.PickerClosed) }

    val back = if (state.picking) ({ onEvent(AllowlistEvent.PickerClosed) }) else onBack
    LfDetailPage(
        title = if (state.picking) "Add an app" else "Payment apps",
        subtitle = if (state.picking) {
            "Apps on this phone. Names only; nothing they posted is read."
        } else {
            ALLOWLIST_EXPLAINER
        },
        modifier = modifier,
        actions = { LfButton(text = "Back", onClick = back, style = LfButtonStyle.Inline) },
        notices = if (state.picking) {
            null
        } else {
            {
                LfActionRow(alignment = LfActionAlignment.Start) {
                    LfButton(
                        text = "Add an app",
                        onClick = { onEvent(AllowlistEvent.AddRequested) },
                        style = LfButtonStyle.Inline,
                    )
                }
            }
        },
    ) {
        if (state.picking) {
            pickerItems(state, onEvent)
        } else {
            items(items = state.apps, key = { it.packageName }, contentType = { "app" }) { app ->
                AllowlistRow(app = app, onEvent = onEvent)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pickerItems(
    state: AllowlistUiState,
    onEvent: (AllowlistEvent) -> Unit,
) {
    if (state.installed.isEmpty()) {
        item(key = "reading", contentType = "note") { Note("Reading the apps on this phone.") }
    }
    items(items = state.installed, key = { it.packageName }, contentType = { "installed" }) { app ->
        InstalledRow(app = app, onPick = { onEvent(AllowlistEvent.Picked(app)) })
    }
}

/** One app on the list: name, whether it is read, and how it got there. */
@Composable
private fun AllowlistRow(app: AllowlistedApp, onEvent: (AllowlistEvent) -> Unit) {
    LfCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            LfSwitchRow(
                label = app.label ?: app.packageName,
                checked = app.enabled,
                onCheckedChange = { onEvent(AllowlistEvent.Toggled(app.packageName, it)) },
                supporting = rowSupporting(app),
                // A full 48 dp, so the Remove under it cannot share its tap
                // area (§9.6; caught by the golden's touch-target check).
                modifier = Modifier.defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget),
            )
            if (!app.builtIn) {
                LfActionRow(alignment = LfActionAlignment.End) {
                    LfButton(
                        text = "Remove",
                        onClick = { onEvent(AllowlistEvent.Removed(app.packageName)) },
                        style = LfButtonStyle.Inline,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(app: InstalledApp, onPick: () -> Unit) {
    LfCard(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .clickable(onClick = onPick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(text = app.label, style = LfTheme.typography.bodyL, color = LfTheme.colors.textPrimary)
            Text(
                text = installedSupporting(app),
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = LfTheme.typography.bodyM,
        color = LfTheme.colors.textSecondary,
    )
}

/**
 * The warning before an app that carries personal messages joins the list.
 * `Warning` emphasis, so an outside tap is not an answer; it names what
 * happens rather than asking "are you sure?".
 */
@Composable
private fun PersonalMessagesWarning(app: InstalledApp, onEvent: (AllowlistEvent) -> Unit) {
    LfDialog(
        title = "Add ${app.label}?",
        body = personalMessagesWarning(app.label),
        confirmText = "Add anyway",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(AllowlistEvent.WarningConfirmed) },
        onDismiss = { onEvent(AllowlistEvent.WarningDismissed) },
    )
}

/** The screen's first sentence: the guarantee, stated as SPEC §5.2 states it. */
internal const val ALLOWLIST_EXPLAINER: String =
    "LedgerFlow reads notifications from these apps only. Nothing from any other app is read or kept."

internal fun rowSupporting(app: AllowlistedApp): String {
    val origin = if (app.builtIn) "Built in" else "Added by you"
    return "$origin · ${breakableAtDots(app.packageName)}"
}

internal fun installedSupporting(app: InstalledApp): String {
    val name = breakableAtDots(app.packageName)
    return if (app.carriesPersonalMessages) "$name · personal messages" else name
}

/**
 * A package name that wraps after a dot rather than mid-word
 * ("com.google.android.apps.nbu.paisa.use / r" was the golden's first draft):
 * a zero-width space after each dot gives the line breaker somewhere to go.
 * Built from its code point, never typed (see `SourceHasNoMangledEscapesTest`).
 */
internal fun breakableAtDots(packageName: String): String = packageName.replace(".", ".$ZERO_WIDTH_SPACE")

private val ZERO_WIDTH_SPACE: Char = Char(0x200B)

/**
 * What adding a messaging, SMS or email app does (SPEC §3.1, §5.1, D-09): every
 * notification read, its text kept 90 days in the encrypted vault, each one
 * that is not a payment queued in the Inbox (nothing is ever silently dropped),
 * and a bank SMS it repeats possibly captured twice.
 */
internal fun personalMessagesWarning(label: String): String =
    "$label shows personal messages. LedgerFlow would read every notification it posts, " +
        "keep the text in its encrypted vault for 90 days, and put each message that isn't a " +
        "payment in your Inbox to dismiss. A bank SMS it repeats may also arrive twice."

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun AllowlistPreview() {
    LfTheme {
        AllowlistScreen(
            state = AllowlistUiState(
                isLoaded = true,
                apps = listOf(
                    AllowlistedApp("com.phonepe.app", "PhonePe", enabled = true, builtIn = true),
                    AllowlistedApp("net.one97.paytm", "Paytm", enabled = false, builtIn = true),
                    AllowlistedApp("com.example.bank", "My Bank", enabled = true, builtIn = false),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
