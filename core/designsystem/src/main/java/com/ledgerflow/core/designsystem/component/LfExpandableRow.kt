package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.ledgerflow.core.designsystem.icon.LfIcons

/**
 * A question that opens to its answer (Help & FAQ, V2-3; DESIGN_SYSTEM_PLAN
 * §5.6). Stateless: the page holds which rows are open.
 *
 * The title is the button. TalkBack hears its state ("Collapsed" / "Expanded")
 * and the action it offers ("Expand" / "Collapse"), so the question is useful
 * before it is opened. No animation: the answer appears where it will stay,
 * and Animations > Off has nothing to switch off.
 *
 * The title wraps rather than clips (BUG9 applies to labels, and a question
 * is not a control's label); the chevron stays centred on its first lines.
 */
@Composable
public fun LfExpandableRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LfExpandableRowTokens
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = tokens.minHeight)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) COLLAPSE else EXPAND,
                    onClick = onToggle,
                )
                .semantics { stateDescription = if (expanded) EXPANDED else COLLAPSED }
                .padding(horizontal = tokens.inset, vertical = tokens.insetVertical),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = tokens.titleStyle,
                color = tokens.title,
                modifier = Modifier.weight(1f),
            )
            // Decoration: the state description says the same thing.
            Icon(
                imageVector = LfIcons.Expand,
                contentDescription = null,
                tint = tokens.chevron,
                modifier = Modifier.rotate(if (expanded) tokens.openRotation else 0f),
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = tokens.inset, end = tokens.inset, bottom = tokens.insetVertical),
            ) { content() }
        }
    }
}

private const val EXPAND = "Expand"
private const val COLLAPSE = "Collapse"
private const val EXPANDED = "Expanded"
private const val COLLAPSED = "Collapsed"
