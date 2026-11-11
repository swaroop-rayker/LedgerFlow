package com.ledgerflow.feature.tools.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The calculator (V2-PLAN §4.4; the owner's mockup of 2026-11-10): the whole
 * sum on its working line, the answer under it in the base currency, a keypad,
 * and "Use as amount", which opens a new entry with the answer typed in. It is
 * reached from Profile > Money tools only; the entry form does not change.
 */
@Composable
public fun CalculatorScreen(
    state: CalculatorUiState,
    onEvent: (CalculatorEvent) -> Unit,
    onUseAmount: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfFormPage(
        title = "Calculator",
        modifier = modifier,
        onBack = onBack,
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Use as amount",
                    onClick = { state.usableMinor?.let(onUseAmount) },
                    enabled = state.usableMinor != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Display(state)
        Keypad(onKey = { onEvent(CalculatorEvent.KeyPressed(it)) })
        Text(
            text = "× and ÷ come before + and −. The answer rounds once, half up.",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
        )
    }
}

@Composable
private fun Display(state: CalculatorUiState) {
    LfPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LfTheme.space.cardInset),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
        ) {
            Text(
                text = state.working,
                style = LfTheme.type.figureLabel,
                color = LfTheme.colors.text.secondary,
                textAlign = LfTextAlign.end,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "Sum: ${state.working.spokenSum()}" },
            )
            Text(
                text = state.result,
                style = LfTheme.type.amountHero,
                color = if (state.problem == null) LfTheme.colors.text.primary else LfTheme.colors.text.muted,
                textAlign = LfTextAlign.end,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.problem?.let {
                Text(text = it, style = LfTheme.type.bodySmall, color = LfTheme.colors.status.danger)
            }
        }
    }
}

/** The owner's layout: C ⌫ ÷ × / 7 8 9 − / 4 5 6 + / 1 2 3 = / 00 0 . %. */
private val KeyRows: List<List<CalcKey>> = listOf(
    listOf(CalcKey.Clear, CalcKey.Backspace, CalcKey.Operator(CalcOp.DIVIDE), CalcKey.Operator(CalcOp.TIMES)),
    listOf(CalcKey.Digit(7), CalcKey.Digit(8), CalcKey.Digit(9), CalcKey.Operator(CalcOp.MINUS)),
    listOf(CalcKey.Digit(4), CalcKey.Digit(5), CalcKey.Digit(6), CalcKey.Operator(CalcOp.PLUS)),
    listOf(CalcKey.Digit(1), CalcKey.Digit(2), CalcKey.Digit(3), CalcKey.Equals),
    listOf(CalcKey.DoubleZero, CalcKey.Digit(0), CalcKey.Point, CalcKey.Percent),
)

/** One panel with hairlines between keys, not a card per key. */
@Composable
private fun Keypad(onKey: (CalcKey) -> Unit) {
    LfPanel {
        KeyRows.forEachIndexed { index, row ->
            if (index > 0) LfDivider()
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { column, key ->
                    if (column > 0) VerticalHairline()
                    Key(key, onKey)
                }
            }
        }
    }
}

@Composable
private fun RowScope.Key(key: CalcKey, onKey: (CalcKey) -> Unit) {
    val isAction = key !is CalcKey.Digit && key != CalcKey.DoubleZero && key != CalcKey.Point
    val color = if (isAction) LfTheme.colors.action.primary else LfTheme.colors.text.primary
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = LfLayout.minTouchTarget)
            .clickable(role = Role.Button) { onKey(key) }
            .clearAndSetSemantics {
                contentDescription = key.spoken()
                role = Role.Button
                onClick { onKey(key); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (key == CalcKey.Backspace) {
            Icon(imageVector = LfIcons.Backspace, contentDescription = null, tint = color)
        } else {
            Text(text = key.label(), style = LfTheme.type.amountRow, color = color, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun VerticalHairline(color: Color = LfTheme.colors.border.subtle) {
    Box(
        modifier = Modifier
            .width(LfTheme.borders.hairline)
            .fillMaxHeight()
            .background(color),
    )
}

internal fun CalcKey.label(): String = when (this) {
    is CalcKey.Digit -> value.toString()
    CalcKey.DoubleZero -> "00"
    CalcKey.Point -> "."
    is CalcKey.Operator -> op.symbol
    CalcKey.Percent -> "%"
    CalcKey.Backspace -> "⌫"
    CalcKey.Clear -> "C"
    CalcKey.Equals -> "="
}

/** What a screen reader says for a key. */
internal fun CalcKey.spoken(): String = when (this) {
    is CalcKey.Digit -> value.toString()
    CalcKey.DoubleZero -> "double zero"
    CalcKey.Point -> "point"
    is CalcKey.Operator -> op.spoken
    CalcKey.Percent -> "percent"
    CalcKey.Backspace -> "delete"
    CalcKey.Clear -> "clear"
    CalcKey.Equals -> "equals"
}

/** "100 + 20 × 3" as "100 plus 20 times 3", so the working line reads as words. */
internal fun String.spokenSum(): String = CalcOp.entries.fold(this) { text, op -> text.replace(op.symbol, op.spoken) }
    .replace("%", " percent")

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun CalculatorPreview() {
    LfTheme {
        CalculatorScreen(state = CalculatorPreviews.working, onEvent = {}, onUseAmount = {}, onBack = {})
    }
}

/** Fixed states for previews and goldens. */
internal object CalculatorPreviews {
    val working = CalculatorUiState(
        currency = "INR",
        working = "100 + 20 × 3",
        result = "₹160.00",
        usableMinor = 160_00,
    )
    val empty = CalculatorUiState(currency = "INR", working = "", result = "₹0.00")
    val divideByZero = CalculatorUiState(
        currency = "INR",
        working = "5 ÷ 0",
        result = "₹0.00",
        problem = "Can't divide by zero",
    )
}
