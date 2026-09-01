package com.ledgerflow.feature.onboarding.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The permission explainer (SPEC.md §5.2).
 *
 * ## Why a screen and not a dialog
 *
 * Notification access **cannot be granted in-app**. It is a system Settings
 * page, reached by an `Intent`, and the app's only role is to explain why it is
 * asking and then confirm afterwards what the user chose. A dialog that sends
 * someone to another app and is gone when they return has no way to tell them
 * whether it worked; a screen is still here on resume, which is where §5.2's
 * poll reports back.
 *
 * ## Two grants, kept visibly separate
 *
 * The rows are not steps in a sequence and are deliberately not numbered. One
 * lets the app *read* payment notifications; the other lets it *show* the user
 * an Inbox notification. Withholding the second costs an announcement and never
 * a candidate — the Inbox fills either way — and a user who grants only the
 * runtime prompt would otherwise have every reason to think they were done.
 *
 * ## The privacy card is not decoration
 *
 * [NOTIFICATION_PRIVACY_RULE] is `SPEC.md` §5.2's own sentence, and
 * `PrivacyRuleIsVerbatimTest` reads the spec at test time to keep it that way.
 * It sits *above* the action that opens system settings rather than below it,
 * because it is the answer to the question the button provokes.
 *
 * Stateless (CLAUDE.md §5): everything comes in as [state], everything leaves
 * through [onEvent]. The two `Intent`-shaped events are handled by
 * [NotificationAccessRoute], not here and not in the ViewModel.
 */
@Composable
public fun NotificationAccessScreen(
    state: NotificationAccessUiState,
    onEvent: (NotificationAccessEvent) -> Unit,
    doneLabel: String,
    modifier: Modifier = Modifier,
) {
    LfScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LfScreenTitle(
                    title = "Notification capture",
                    subtitle = "Most UPI payments never send an SMS. Reading the " +
                        "notification is how they reach your Inbox.",
                    modifier = Modifier.weight(1f),
                )
                LfButton(
                    text = doneLabel,
                    style = LfButtonStyle.Text,
                    onClick = { onEvent(NotificationAccessEvent.Done) },
                    modifier = Modifier.padding(end = LfTheme.spacing.md),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                PrivacyCard()

                GrantCard(
                    title = "Read payment notifications",
                    body = "Granted in Android's own settings — LedgerFlow cannot " +
                        "turn this on for you.",
                    granted = state.listenerGranted,
                    polled = state.polled,
                    actionLabel = "Open settings",
                    onAction = { onEvent(NotificationAccessEvent.OpenListenerSettings) },
                )

                if (state.postNotificationsApplicable) {
                    GrantCard(
                        title = "Tell you when something arrives",
                        // Says what declining costs, in the row where declining
                        // happens. Nothing here is load-bearing for capture, and
                        // a user who thinks it is will grant it for the wrong
                        // reason and then stop reading.
                        body = "Optional. Without it, candidates still reach your " +
                            "Inbox — they just wait there quietly.",
                        granted = state.postNotificationsGranted,
                        polled = state.polled,
                        actionLabel = "Allow",
                        onAction = { onEvent(NotificationAccessEvent.RequestPostNotifications) },
                    )
                }
            }
        }
    }
}

/**
 * §5.2's privacy hard rule, quoted.
 *
 * The same `LfCard` container as the two grant rows, because one screen gets one
 * shape — a differently-styled callout here would read as a second design the
 * moment the user scrolls past it.
 */
@Composable
private fun PrivacyCard() {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "What LedgerFlow reads",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = NOTIFICATION_PRIVACY_RULE,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * One grant: what it is, whether it is held, and the one control that changes it.
 *
 * The status is a chip rather than a sentence, so the answer to "am I set up"
 * is readable without reading. It renders nothing at all until [polled], because
 * the honest pre-poll answer is "not asked yet" and drawing that as "Not
 * granted" makes a correctly-configured install flash a warning every time the
 * screen opens.
 *
 * The action stays enabled when the grant is already held: "Open settings" is
 * still the route to *revoking* it, and a disabled control on a screen about
 * permissions reads as the app having taken the decision away.
 */
@Composable
private fun GrantCard(
    title: String,
    body: String,
    granted: Boolean,
    polled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = body,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            // LfActionRow rather than a bare Row: at font scale 2.0 the chip and
            // the label stop sharing a line, and the container is what has to
            // wrap (BUG9). A label never does.
            LfActionRow(alignment = LfActionAlignment.End) {
                if (polled) {
                    LfChip(
                        label = if (granted) "On" else "Off",
                        style = if (granted) LfChipStyle.Selected else LfChipStyle.Warning,
                        contentDescription = if (granted) {
                            "$title: on"
                        } else {
                            "$title: off"
                        },
                    )
                }
                LfButton(
                    text = actionLabel,
                    style = LfButtonStyle.Inline,
                    onClick = onAction,
                )
            }
        }
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun NotificationAccessPreview() {
    LfTheme {
        NotificationAccessScreen(
            state = NotificationAccessUiState(
                listenerGranted = false,
                postNotificationsGranted = true,
                postNotificationsApplicable = true,
                polled = true,
            ),
            onEvent = {},
            doneLabel = "Not now",
        )
    }
}

@PreviewFontScale
@PreviewLightDark
@Composable
private fun NotificationAccessGrantedPreview() {
    LfTheme {
        NotificationAccessScreen(
            state = NotificationAccessUiState(
                listenerGranted = true,
                postNotificationsGranted = true,
                postNotificationsApplicable = true,
                polled = true,
            ),
            onEvent = {},
            doneLabel = "Done",
        )
    }
}
