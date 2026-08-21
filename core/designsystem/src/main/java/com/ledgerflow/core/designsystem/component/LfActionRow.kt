package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * A cluster of controls that wraps **whole controls** onto the next line (BUG9).
 *
 * A plain `Row` overflows once the labels or the font scale grow, and the
 * overflowing control's label breaks mid-word -- which is how "Delete" shipped
 * as "Delet" above a lone "e". `LfButton` refuses to wrap its label
 * (`softWrap = false`), so it measures at its natural width; this is the other
 * half of that contract, giving the excess somewhere to go.
 *
 * Use this for every row of two or more actions. §9.6 requires 2.0x font scale
 * without truncation or overlap, and at 2.0x almost any three-action row
 * overflows a phone-width card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun LfActionRow(
    modifier: Modifier = Modifier,
    alignment: LfActionAlignment = LfActionAlignment.Center,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            LfTheme.spacing.sm,
            when (alignment) {
                LfActionAlignment.Start -> Alignment.Start
                LfActionAlignment.Center -> Alignment.CenterHorizontally
                LfActionAlignment.End -> Alignment.End
            },
        ),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        content()
    }
}

/**
 * Where the controls sit on each line.
 *
 * [Center] is the default because of how wrapping looks: when a third action
 * drops to its own line, a left-aligned one hangs off the bottom corner of the
 * card looking like a mistake, where a centred one reads as deliberate.
 *
 * [End] is for dialogs, where convention puts the confirming action on the
 * trailing edge and users reach for it there.
 *
 * [Start] is for clusters that are not actions at all -- a row of informational
 * chips under a card heading, where centring would leave them floating away
 * from the text they belong to. The reasoning behind [Center] does not apply,
 * because nothing here is a control the eye has to find.
 */
public enum class LfActionAlignment { Start, Center, End }
