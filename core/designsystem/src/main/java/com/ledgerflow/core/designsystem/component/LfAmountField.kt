package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.CurrencyDisplay

/**
 * The amount being entered, shown large and typed on the system keyboard
 * (SPEC.md §9.4).
 *
 * **This used to be a display beside an in-app keypad, and the keypad is gone.**
 * The keypad's argument was that appending digits right-to-left keeps the amount
 * an integer with no string to parse, and that the system IME cannot be trusted
 * to offer digits. Both were true and neither was decisive: an accumulator makes
 * typing `125` mean ₹1.25, which is right for a payments app and wrong for a
 * ledger, where you are usually transcribing an exact figure off a receipt.
 * ADR-0012 records the reversal.
 *
 * What replaces the safety argument is [MoneyFormat.parse], which converts the
 * typed text to minor units with integer arithmetic only. So the value is still
 * a `Long` everywhere below this composable, and the untrustworthy-keyboard case
 * is handled by discarding anything that is not a digit rather than by refusing
 * to use the keyboard at all.
 *
 * The field holds **raw text**, not a formatted value. Reformatting while
 * someone is typing moves the caret out from under their thumb, which is the
 * single most common way a money field becomes unusable.
 *
 * The typography is `amountL`, which carries `tnum` (§9.2) — without tabular
 * figures the number jitters sideways as each digit lands, because a "1" is
 * narrower than an "8".
 */
@Composable
public fun LfAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    tone: LfAmountTone = LfAmountTone.Neutral,
    secondary: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing

    val amountColor = when (tone) {
        LfAmountTone.Neutral -> colors.textPrimary
        LfAmountTone.Debit -> colors.debit
        LfAmountTone.Credit -> colors.credit
    }

    // Announced as words, from the parsed value rather than the raw text, so a
    // half-typed "12." is read as an amount and not as punctuation (§9.6).
    val spokenAmount = MoneyFormat.spoken(MoneyFormat.parse(value, currencyCode), currencyCode)
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

        AmountInput(
            value = value,
            onValueChange = onValueChange,
            currencyCode = currencyCode,
            amountColor = amountColor,
            focusRequester = focusRequester,
        )

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
 * The field itself, split out of [LfAmountField] so neither half is long enough
 * to hide anything: this is the text-input contract, the caller owns the
 * label, tone and spoken description around it.
 */
@Composable
private fun AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    currencyCode: String,
    amountColor: androidx.compose.ui.graphics.Color,
    focusRequester: FocusRequester?,
) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing
    val focusManager = LocalFocusManager.current

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = SELECTION_ALPHA),
        ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .widthIn(min = MIN_FIELD_WIDTH)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            // Start, not Center. A `BasicTextField` measures to the width it is
            // offered rather than to its text, so centring the glyphs inside it
            // strands the currency symbol several centimetres to the left of the
            // number it belongs to -- which is what the first build did.
            textStyle = LfTheme.typography.amountL.copy(
                color = amountColor,
                textAlign = TextAlign.Start,
            ),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                // A hint, not a guarantee -- some OEM keyboards ignore it and
                // serve QWERTY. The parser is what makes that harmless.
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            // Done dismisses the keyboard; it deliberately does not save. A form
            // whose keyboard commits an expense is a form that commits expenses
            // by accident.
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { innerTextField ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = CurrencyDisplay.symbolOf(currencyCode),
                        style = LfTheme.typography.titleL,
                        color = colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = MoneyFormat.plain(0L, currencyCode),
                                style = LfTheme.typography.amountL,
                                color = colors.textTertiary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

/**
 * Which book's colour an amount wears (§9.1).
 *
 * [Neutral] is the default and is what the entry form uses while the field is
 * still empty: colouring a zero before the user has entered anything reads as
 * an error rather than as an expense.
 *
 * Declared after the composable to match the rest of `component/`: detekt's
 * MatchingDeclarationName fires when a file's *first* top-level declaration is
 * a class whose name is not the filename.
 */
public enum class LfAmountTone { Neutral, Debit, Credit }

private const val SELECTION_ALPHA = 0.3f

/**
 * Enough room for a caret and a couple of digits.
 *
 * `IntrinsicSize.Min` sizes the field to its text, which is what keeps the
 * currency symbol beside the number -- but an empty field would then collapse
 * to nothing and be untappable.
 */
private val MIN_FIELD_WIDTH = 96.dp
