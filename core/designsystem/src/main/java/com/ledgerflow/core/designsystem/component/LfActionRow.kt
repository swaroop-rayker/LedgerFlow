package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        content()
    }
}
