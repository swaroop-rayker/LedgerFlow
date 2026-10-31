package com.ledgerflow.feature.settings.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfExpandableRow
import com.ledgerflow.core.designsystem.component.LfProse
import com.ledgerflow.core.designsystem.component.LfProseTone
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.component.lfProseItems
import com.ledgerflow.core.designsystem.format.ProseMarkdown
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * What's new, the privacy policy or the terms (Settings > About, V2-3): a
 * detail page of `LfProse`, one lazy item per block, so a long policy composes
 * only what is on screen.
 */
@Composable
public fun AboutDocumentScreen(state: AboutDocumentUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LfDetailPage(title = state.document.title, modifier = modifier, onBack = onBack) {
        state.blocks?.let { lfProseItems(it, keyPrefix = state.document.name) }
    }
}

/**
 * Help & FAQ (V2-3): every question in one panel, each opening to its answer.
 * Closed by default, so the page is a list to scan rather than an essay.
 */
@Composable
public fun HelpScreen(state: HelpUiState, onToggle: (Int) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LfDetailPage(title = "Help & FAQ", modifier = modifier, onBack = onBack) {
        val items = state.items ?: return@LfDetailPage
        item(key = "questions", contentType = "panel") {
            LfPanel {
                items.forEachIndexed { index, help ->
                    if (index > 0) LfDivider()
                    LfExpandableRow(
                        title = help.question,
                        expanded = index in state.open,
                        onToggle = { onToggle(index) },
                    ) { LfProse(help.answer, tone = LfProseTone.Secondary) }
                }
            }
        }
    }
}

/**
 * Report a bug (V2-PLAN §3.8): the user writes what happened and sees, in
 * full, the device details that go with it. **Share** hands that exact text to
 * an app the user picks; LedgerFlow sends nothing itself (Law 6), and there is
 * no address to send to until the owner publishes one.
 */
@Composable
public fun ReportBugScreen(
    state: ReportBugUiState,
    onDescribe: (String) -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfFormPage(
        title = "Report a bug",
        subtitle = "Nothing leaves your phone until you choose an app to send it with.",
        modifier = modifier,
        onBack = onBack,
        actionBar = {
            LfActionBar { LfButton(text = "Share report", onClick = onShare, enabled = state.canShare) }
        },
    ) {
        LfTextField(
            value = state.description,
            onValueChange = onDescribe,
            label = "What happened?",
            supportingText = "What you did, what you expected, and what you saw instead.",
            singleLine = false,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
            Text(
                text = "Sent with your report",
                style = LfTheme.type.label,
                color = LfTheme.colors.text.secondary,
            )
            LfPanel {
                // Plex, as figures and identifiers are: the reader is checking
                // what will be sent, character for character.
                Text(
                    text = state.details.joinToString("\n"),
                    style = LfTheme.type.figureLabel,
                    color = LfTheme.colors.text.primary,
                    modifier = Modifier.fillMaxWidth().padding(LfTheme.space.cardInset),
                )
            }
            Text(
                text = "No entries, amounts, messages or settings are included.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
    }
}

/**
 * The share sheet over [text]: Android's chooser, so the user picks the app
 * every time and nothing is remembered as a default.
 */
public fun bugReportIntent(text: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, REPORT_SUBJECT)
        .putExtra(Intent.EXTRA_TEXT, text)
    return Intent.createChooser(send, REPORT_SUBJECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens [bugReportIntent]. A phone with no app that takes text loses a tap, not the app. */
public fun shareBugReport(context: Context, text: String) {
    try {
        context.startActivity(bugReportIntent(text))
    } catch (_: ActivityNotFoundException) {
        // Nothing to share with; the text is still on screen.
    }
}

internal const val REPORT_SUBJECT: String = "LedgerFlow bug report"

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ReportBugPreview() {
    LfTheme {
        ReportBugScreen(
            state = ReportBugUiState(
                description = "The Inbox showed a payment twice.",
                details = listOf("App: LedgerFlow 0.1.1 (2)", "Android: 16 (API 36)"),
            ),
            onDescribe = {},
            onShare = {},
            onBack = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun HelpPreview() {
    LfTheme {
        HelpScreen(
            state = HelpUiState(
                items = listOf(HelpItem("Where is my data kept?", ProseMarkdown.parse("On this phone."))),
                open = setOf(0),
            ),
            onToggle = {},
            onBack = {},
        )
    }
}
