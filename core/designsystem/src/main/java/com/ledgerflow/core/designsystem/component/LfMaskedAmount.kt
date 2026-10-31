package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.format.AmountMask
import com.ledgerflow.core.model.LedgerType

/**
 * An amount that can be hidden (Hide amounts on Home, V2-PLAN §3.5, SPEC §9.6).
 *
 * Shown, it is the figure, one line and never wrapped (the amount roles'
 * rule). Hidden, it is [AmountMask]'s four dots and TalkBack says "amount
 * hidden": **the figure is not in the semantics tree at all**, so neither a
 * screen reader nor an accessibility service reading the window can recover
 * it. `LfMaskedAmountTest` holds that.
 *
 * It does not reveal itself. One tap shows every amount on the screen, so the
 * tap belongs to the screen's container ([lfRevealAmounts]), which is also
 * what gives a small figure a full-size touch target.
 *
 * @param amount the figure as the screen formats it (`MoneyFormat`).
 * @param spoken what TalkBack says while it is shown; `MoneyFormat.spoken`,
 *   since screen readers skip currency glyphs (§9.6).
 * @param ledger the book, for an amount drawn with its sign
 *   (`MoneyFormat.directional`): the mask keeps the sign, as the figure had it.
 */
@Composable
public fun LfMaskedAmount(
    amount: String,
    hidden: Boolean,
    currencyCode: String,
    spoken: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LfMaskedAmountTokens.style,
    color: Color = LfMaskedAmountTokens.content,
    textAlign: TextAlign? = null,
    ledger: LedgerType? = null,
) {
    val description = if (hidden) AmountMask.SPOKEN else spoken
    val mask = if (ledger != null) AmountMask.directional(currencyCode, ledger) else AmountMask.symbolised(currencyCode)
    Text(
        text = if (hidden) mask else amount,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Makes a block of hidden amounts the way to show them: while [hidden], a tap
 * anywhere on it calls [onReveal], and a screen reader is offered
 * [AmountMask.REVEAL_LABEL]. Shown, it adds nothing, so the block is exactly
 * what it was before Hide amounts existed.
 *
 * At least the touch target tall while hidden (§9.6), for a one-line block
 * such as Home's "Spent today". No ripple: the block is usually a whole card,
 * whose rounded shape a ripple would square off, and the figures appearing
 * are the feedback.
 */
public fun Modifier.lfRevealAmounts(hidden: Boolean, onReveal: () -> Unit): Modifier =
    if (!hidden) {
        this
    } else {
        this
            .defaultMinSize(minHeight = LfMaskedAmountTokens.revealMinHeight)
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = AmountMask.REVEAL_LABEL,
                role = Role.Button,
                onClick = onReveal,
            )
    }
