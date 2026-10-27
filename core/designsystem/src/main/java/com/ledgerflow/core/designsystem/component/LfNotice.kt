package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * A message inside the content area (DESIGN_SYSTEM_PLAN §5.1, §5.2, step E1):
 * a result that matters later ("Backup written"), something to know ("Capture
 * is reconnecting"), something to fix ("Notification access is off").
 *
 * One design for every screen. Until E1 each screen drew its own banner (the
 * Ledger's, Organise's and the bin's `MessageBanner`s, Recovery's
 * `FailureMessage`, Restore's `ResultMessage`...); E2–E6 move them here.
 *
 * The tone's faint fill with its colour as the edge, so the kind is visible at
 * a glance -- and the title always says it in words too, since colour alone
 * carries nothing to a screen reader or to colour-blind eyes. The action is an
 * in-card `Inline` button at the end, as every in-card action is (`CLAUDE.md`
 * §5). Success and warning notices are announced politely when they appear.
 */
@Composable
public fun LfNotice(
    tone: LfNoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = LfNoticeTokens
    val (edge, fill) = when (tone) {
        LfNoticeTone.Info -> tokens.infoEdge to tokens.infoFill
        LfNoticeTone.Success -> tokens.successEdge to tokens.successFill
        LfNoticeTone.Warning -> tokens.warningEdge to tokens.warningFill
    }
    NoticeBox(
        edge = edge,
        fill = fill,
        announce = tone != LfNoticeTone.Info,
        title = title,
        body = body,
        action = if (actionLabel != null && onAction != null) actionLabel to onAction else null,
        modifier = modifier,
    )
}

/**
 * Something failed: what happened, what is safe, and what to do (§5.1).
 *
 * The action is required -- usually Retry, or the way out when retrying cannot
 * help. Announced at once: an error the user does not hear about is a silent
 * no-op, the thing §5.2 forbids.
 */
@Composable
public fun LfErrorNotice(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    NoticeBox(
        edge = LfNoticeTokens.errorEdge,
        fill = LfNoticeTokens.errorFill,
        announce = true,
        title = title,
        body = body,
        action = actionLabel to onAction,
        modifier = modifier,
    )
}

@Composable
private fun NoticeBox(
    edge: Color,
    fill: Color,
    announce: Boolean,
    title: String,
    body: String?,
    action: Pair<String, () -> Unit>?,
    modifier: Modifier,
) {
    val tokens = LfNoticeTokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite }
            .background(fill, tokens.shape)
            .border(tokens.edgeWidth, edge, tokens.shape)
            .padding(horizontal = tokens.insetHorizontal, vertical = tokens.insetVertical),
        verticalArrangement = Arrangement.spacedBy(tokens.gap),
    ) {
        Text(text = title, style = tokens.titleStyle, color = tokens.text)
        body?.let { Text(text = it, style = tokens.bodyStyle, color = tokens.text) }
        action?.let { (label, onClick) ->
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(text = label, onClick = onClick, style = LfButtonStyle.Inline)
            }
        }
    }
}

/**
 * How a notice reads: what kind of news it is.
 *
 * There is no error tone here on purpose. An error is [LfErrorNotice], which
 * cannot be built without an action: DESIGN_SYSTEM_PLAN §5.1's rule is "no
 * error state without an action", and a type that cannot express the
 * violation keeps it rather than a test that has to find it.
 *
 * Declared after the composables, as `LfChip.kt` does: detekt's
 * MatchingDeclarationName fires when a file's first top-level declaration is a
 * class whose name is not the filename.
 */
public enum class LfNoticeTone { Info, Success, Warning }
