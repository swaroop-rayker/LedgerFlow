package com.ledgerflow.feature.tools.converter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfFeedback
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.picker.LfPickerDialog

/**
 * The converter (ADR-0036; the owner's mockup of 2026-11-10): an amount in one
 * currency as another, at the ECB's reference rate or at the user's own.
 *
 * **For reference only.** It has no "Use as amount" and never fills an
 * entry's rate (§6): the figure for an entry is what the bank charged, which
 * the review asks for. **"Get today's rates" is the only thing that fetches**
 * (§3), and every fetched figure carries the ECB's own date (§5).
 */
@Composable
public fun ConverterScreen(
    state: ConverterUiState,
    onEvent: (ConverterEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LfFeedback.current
    state.message?.let { message ->
        LaunchedEffect(message) {
            feedback.post(message)
            onEvent(ConverterEvent.MessageShown)
        }
    }
    CurrencyPicker(state, onEvent)
    LfFormPage(title = "Converter", actionBar = null, modifier = modifier, onBack = onBack) {
        Amounts(state, onEvent)
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(text = "Swap", onClick = { onEvent(ConverterEvent.SwapClicked) }, style = LfButtonStyle.Inline)
            LfButton(
                text = "Get today's rates",
                onClick = { onEvent(ConverterEvent.FetchClicked) },
                style = LfButtonStyle.Inline,
                loading = state.fetching,
            )
        }
        Rates(state, onEvent)
        state.rateLine?.let {
            Text(text = it, style = LfTheme.type.figureLabel, color = LfTheme.colors.text.secondary)
        }
        Text(
            text = "For reference only. An entry in another currency still takes the amount your bank " +
                "charged, which you enter at review.",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
        )
    }
}

/** From and To: each a currency row, the amount typed under From and the answer under To. */
@Composable
private fun Amounts(state: ConverterUiState, onEvent: (ConverterEvent) -> Unit) {
    LfPanel {
        Column(modifier = Modifier.padding(horizontal = LfTheme.space.rowInset)) {
            LfDetailRow(
                label = "From",
                value = state.from,
                onClick = { onEvent(ConverterEvent.PickerOpened(ConverterSide.FROM)) },
            )
            LfAmountField(
                value = state.amountText,
                onValueChange = { onEvent(ConverterEvent.AmountChanged(it)) },
                currencyCode = state.from,
                label = "Amount",
                // A gap under it, so its touch area never overlaps the To row's (SPEC §9.6).
                modifier = Modifier.padding(bottom = LfTheme.space.itemGap),
            )
            LfDivider()
            LfDetailRow(
                label = "To",
                value = state.to,
                onClick = { onEvent(ConverterEvent.PickerOpened(ConverterSide.TO)) },
            )
            Text(
                text = state.result.ifEmpty { "—" },
                style = LfTheme.type.amountHero,
                color = if (state.result.isEmpty()) LfTheme.colors.text.muted else LfTheme.colors.text.primary,
                // Centred, as the amount typed above it is.
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LfTheme.space.rowInsetVertical),
            )
        }
    }
}

/** The ECB's rate and the user's own, one chosen; the ECB's only when it has both currencies. */
@Composable
private fun Rates(state: ConverterUiState, onEvent: (ConverterEvent) -> Unit) {
    // Clipped, so the chosen row's fill keeps the panel's rounded corners.
    LfPanel(modifier = Modifier.clip(LfTheme.shapes.container)) {
        RateRow(
            title = "ECB rate",
            caption = state.ecbCaption,
            figure = state.ecbRate,
            selected = state.choice == RateChoice.ECB,
            enabled = state.ecbAvailable,
            onClick = { onEvent(ConverterEvent.ChoicePicked(RateChoice.ECB)) },
        )
        LfDivider()
        RateRow(
            title = "Your rate",
            caption = "${state.to} for one ${state.from}",
            figure = null,
            selected = state.choice == RateChoice.TYPED,
            enabled = true,
            onClick = { onEvent(ConverterEvent.ChoicePicked(RateChoice.TYPED)) },
        )
        LfTextField(
            value = state.typedRateText,
            onValueChange = { onEvent(ConverterEvent.TypedRateChanged(it)) },
            label = "Rate",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LfTheme.space.rowInset)
                .padding(bottom = LfTheme.space.rowInsetVertical),
        )
    }
}

/** One choice of rate: a whole-row radio, marked by a fill as `LfChoiceRow` is (BUG83). */
@Composable
private fun RateRow(
    title: String,
    caption: String,
    figure: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val text = if (enabled) LfTheme.colors.text.primary else LfTheme.colors.text.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(LfTheme.colors.surface.overlay) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
                Text(text = title, style = LfTheme.type.body, color = text)
                Text(text = caption, style = LfTheme.type.label, color = LfTheme.colors.text.muted)
            }
        }
        // `LfAdaptiveRow` needs something on its trailing side; "Your rate" has no figure.
        if (figure == null) {
            words()
        } else {
            LfAdaptiveRow(
                leading = words,
                trailing = { Text(text = figure, style = LfTheme.type.amountSmall, color = text) },
            )
        }
    }
}

@Composable
private fun CurrencyPicker(state: ConverterUiState, onEvent: (ConverterEvent) -> Unit) {
    val side = state.openPicker ?: return
    LfPickerDialog(
        title = if (side == ConverterSide.FROM) "From" else "To",
        options = state.currencyOptions,
        selectedId = if (side == ConverterSide.FROM) state.from else state.to,
        onSelect = { code -> code?.let { onEvent(ConverterEvent.CurrencyPicked(it)) } },
        onDismiss = { onEvent(ConverterEvent.PickerDismissed) },
        query = state.pickerQuery,
        onQueryChange = { onEvent(ConverterEvent.PickerQueryChanged(it)) },
        queryLabel = "Search currencies",
        emptyMessage = "No currency matches.",
    )
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ConverterPreview() {
    LfTheme { ConverterScreen(state = ConverterPreviews.ecb, onEvent = {}, onBack = {}) }
}

/** Fixed states for previews and goldens. */
internal object ConverterPreviews {
    val ecb = ConverterUiState(
        loading = false,
        from = "USD",
        to = "INR",
        amountText = "120.00",
        result = "₹11,272.42",
        choice = RateChoice.ECB,
        ecbAvailable = true,
        ecbRate = "93.9368",
        ecbCaption = "Indicative · ECB reference rate of 9 Nov 2026",
        rateLine = "1 USD = 93.9368 INR",
    )
    val noTable = ConverterUiState(
        loading = false,
        from = "USD",
        to = "INR",
        choice = RateChoice.TYPED,
        ecbCaption = "No rates yet. Get today's rates, or type your own.",
    )
    val unpublished = ConverterUiState(
        loading = false,
        from = "AED",
        to = "INR",
        amountText = "500",
        result = "₹11,350.00",
        choice = RateChoice.TYPED,
        ecbCaption = "The ECB doesn't publish AED. Type your own rate.",
        typedRateText = "22.70",
        rateLine = "1 AED = 22.7000 INR",
    )
}
