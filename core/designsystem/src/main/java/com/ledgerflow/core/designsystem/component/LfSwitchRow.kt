package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * A labelled switch whose **whole row is the control**.
 *
 * Extracted after the budget editor's rollover toggle shipped as a bare
 * `Switch` in a `Row` — the only raw `Switch` in the app — and failed on device
 * in the two ways that arrangement always fails:
 *
 * - **The label did nothing.** Only the 40dp switch responded, on a row that
 *   reads as one control.
 * - **The state and its name were two unrelated nodes.** A screen reader
 *   reached a switch with nothing to say what would roll over, and separately
 *   some words (§9.6).
 *
 * `Modifier.toggleable` on the row fixes both at once: it makes the row the
 * target, and it merges descendants, so the label's node *is* the toggleable
 * node. A `semantics(mergeDescendants = true)` beside it looks like the fix for
 * the second half and is redundant — verified by removing it, which changes
 * nothing, and then by removing `toggleable`, which turns both tests red.
 *
 * `onCheckedChange` on the switch is deliberately `null`: the row already
 * handles the click, and a second handler would toggle twice for a tap landing
 * on the switch itself.
 */
@Composable
public fun LfSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /**
     * A row-sized title and sentence, for a switch that stands as a row among
     * other rows (Settings > Security & privacy), so it reads in the same type
     * as its neighbours. The default stays the compact form forms use.
     */
    prominent: Boolean = false,
    /** The supporting line in Plex Mono, for an identifier (a package name, step F5). */
    figureSupporting: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = if (prominent) {
                Arrangement.spacedBy(LfSwitchRowTokens.prominentGap)
            } else {
                Arrangement.Top
            },
        ) {
            Text(
                text = label,
                style = if (prominent) LfSwitchRowTokens.prominentTitleStyle else LfSwitchRowTokens.titleStyle,
                color = LfSwitchRowTokens.title,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = when {
                        figureSupporting -> LfSwitchRowTokens.figureSupportingStyle
                        prominent -> LfSwitchRowTokens.prominentSupportingStyle
                        else -> LfSwitchRowTokens.supportingStyle
                    },
                    color = LfSwitchRowTokens.supporting,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
