package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.CurrencyDisplay

/**
 * The amount being entered, shown large (SPEC.md §9.4).
 *
 * Display only — it never owns the value and has no text cursor. The keypad
 * builds a `Long` of minor units and this renders it, which is what keeps Law 3
 * true all the way to the glass: there is no point at which a partially-typed
 * amount exists as a string that something might parse into a `Double`.
 *
 * The typography is `amountL`, which carries `tnum` (§9.2). Tabular figures
 * matter more here than anywhere: without them the whole number jitters
 * sideways as each digit is typed, because a "1" is narrower than an "8".
 *
 * **The whole block speaks as one thing.** Left alone, TalkBack would read
 * "rupee", "1,240.50" and the foreign line as three separate nodes; §9.6 asks
 * for "1,240 rupees", so the parts are merged and one composed description is
 * set on the merged node.
 *
 * Merged rather than `clearAndSetSemantics`, which was the first attempt and is
 * wrong twice over: it deletes the children's semantics outright, so the
 * amount stops being findable by text *and* its `TextLayoutResult` becomes
 * unreachable — which is precisely how BUG9's regression tests measure whether
 * a label wrapped. Silencing a node for a screen reader should not blind the
 * test suite to it.
 */
@Composable
public fun LfAmountField(
    minorUnits: Long,
    currencyCode: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    tone: LfAmountTone = LfAmountTone.Neutral,
    secondary: String? = null,
) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing

    val amountColor = when (tone) {
        LfAmountTone.Neutral -> colors.textPrimary
        LfAmountTone.Debit -> colors.debit
        LfAmountTone.Credit -> colors.credit
    }

    val spokenAmount = MoneyFormat.spoken(minorUnits, currencyCode)
    val description = listOfNotNull(label, spokenAmount, secondary).joinToString(", ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        label?.let {
            Text(text = it, style = LfTheme.typography.label, color = colors.textSecondary)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = CurrencyDisplay.symbolOf(currencyCode),
                style = LfTheme.typography.titleL,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = MoneyFormat.plain(minorUnits, currencyCode),
                style = LfTheme.typography.amountL,
                color = amountColor,
                textAlign = TextAlign.Center,
                // An amount is a control's value, and BUG9's rule applies: it
                // is never broken across lines. A number that wraps stops being
                // a number you can read at a glance.
                maxLines = 1,
                softWrap = false,
            )
        }

        secondary?.let {
            Text(
                text = it,
                style = LfTheme.typography.bodyM,
                color = colors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Which book's colour an amount wears (§9.1).
 *
 * [Neutral] is the default and is what the entry form uses while the amount is
 * still being typed: colouring it before the user has chosen a ledger would be
 * the UI asserting something the user has not said yet.
 *
 * Declared after the composable to match the rest of `component/`: detekt's
 * MatchingDeclarationName fires when a file's *first* top-level declaration is
 * a class whose name is not the filename.
 */
public enum class LfAmountTone { Neutral, Debit, Credit }
