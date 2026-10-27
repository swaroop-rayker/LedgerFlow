package com.ledgerflow.core.designsystem.pattern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNavRow
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/*
 * The three sheets the reference samples use (DESIGN_SYSTEM_PLAN §3, step E1),
 * each an [LfSheet] with its parts laid out once: a choice among places to go,
 * a confirmation, and a set of filters.
 */

/** One choice in an [LfOptionsSheet]. [value] is the short fact beside it ("3" waiting). */
@Immutable
public class LfSheetOption(
    public val label: String,
    public val onClick: () -> Unit,
    public val value: String? = null,
)

/**
 * A short list of places to go -- the Add sheet's Scan, Manual entry and
 * Inbox. Each is an `LfNavRow` in one panel, the same row Profile and the
 * Settings index use, so a choice in a sheet reads like a choice on a page.
 * The icon tile the samples put at each row's start arrives in step F3.
 */
@Composable
public fun LfOptionsSheet(
    options: List<LfSheetOption>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    LfSheet(onDismiss = onDismiss, modifier = modifier, title = title) {
        LfPanel {
            options.forEachIndexed { index, option ->
                if (index > 0) LfDivider()
                LfNavRow(title = option.label, value = option.value, onClick = option.onClick)
            }
        }
    }
}

/**
 * A question with two answers: what happens, in one line, and No / Yes. For a
 * consequential step that can be undone ("Sign out of Google Drive?"). An
 * irreversible one -- erase for good, delete every receipt -- stays a
 * `Warning` `LfDialog` that names the count (§5.2), which a sheet is too easy to
 * swipe past to be.
 */
@Composable
public fun LfConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
) {
    LfSheet(onDismiss = onDismiss, modifier = modifier, title = title) {
        Text(text = body, style = LfTheme.type.body, color = LfTheme.colors.text.secondary)
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(text = dismissLabel, onClick = onDismiss, style = LfButtonStyle.Tonal)
            LfButton(text = confirmLabel, onClick = onConfirm)
        }
    }
}

/** One filter choice: a chip that is on or off. */
@Immutable
public class LfFilterChoice(
    public val label: String,
    public val selected: Boolean,
    public val onToggle: () -> Unit,
)

/** A named set of [LfFilterChoice]s ("Category", "Source"). */
@Immutable
public class LfFilterGroup(
    public val label: String,
    public val choices: List<LfFilterChoice>,
)

/**
 * Filters as chip groups, with Reset and Apply. Choices take effect on
 * Apply, so a person can try a few and back out of all of them.
 *
 * The groups scroll inside [LfLayout.dialogListMax], so Reset and Apply stay
 * on screen however many choices there are -- the same limit every list inside
 * a dialog keeps. Each group's name is a heading, so TalkBack can move between
 * groups, and each chip says whether it is selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun LfFilterSheet(
    title: String,
    groups: List<LfFilterGroup>,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    resetLabel: String = "Reset",
    applyLabel: String = "Apply",
) {
    val space = LfTheme.space
    LfSheet(onDismiss = onDismiss, modifier = modifier, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LfLayout.dialogListMax)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(space.sectionGap),
        ) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(space.itemGap)) {
                    Text(
                        text = group.label,
                        style = LfTheme.type.label,
                        color = LfTheme.colors.text.secondary,
                        modifier = Modifier.semantics { heading() },
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(space.itemGap),
                        verticalArrangement = Arrangement.spacedBy(space.itemGap),
                    ) {
                        group.choices.forEach { choice ->
                            LfChip(
                                label = choice.label,
                                style = if (choice.selected) LfChipStyle.Selected else LfChipStyle.Assist,
                                onClick = choice.onToggle,
                            )
                        }
                    }
                }
            }
        }
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(text = resetLabel, onClick = onReset, style = LfButtonStyle.Text)
            LfButton(text = applyLabel, onClick = onApply)
        }
    }
}
