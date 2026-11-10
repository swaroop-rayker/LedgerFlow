package com.ledgerflow.feature.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import com.ledgerflow.feature.tools.calculator.CalculatorPreviews
import com.ledgerflow.feature.tools.calculator.CalculatorScreen
import com.ledgerflow.feature.tools.calculator.CalculatorUiState
import com.ledgerflow.feature.tools.converter.ConverterPreviews
import com.ledgerflow.feature.tools.converter.ConverterScreen
import com.ledgerflow.feature.tools.converter.ConverterUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Utilities (V2-7): the owner's mockups of 2026-11-10 as goldens. 1x and 2x,
 * RTL, and 1.15x, the owner's text size, where the keypad's labels and the
 * converter's two inline actions must keep whole (BUG9).
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class UtilitiesGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { LfScaffold { content() } } }

    @Composable
    private fun Calculator(state: CalculatorUiState) =
        CalculatorScreen(state = state, onEvent = {}, onUseAmount = {}, onBack = {})

    @Composable
    private fun Converter(state: ConverterUiState) = ConverterScreen(state = state, onEvent = {}, onBack = {})

    @Test fun calculator_1x() = capture("calculator-1x", 1f) { Calculator(CalculatorPreviews.working) }

    @Config(qualifiers = PHONE_2X)
    @Test fun calculator_2x() = capture("calculator-2x", 2f) { Calculator(CalculatorPreviews.working) }

    @Test fun calculator_rtl_1x() =
        capture("calculator-rtl-1x", 1f, LayoutDirection.Rtl) { Calculator(CalculatorPreviews.working) }

    @Test fun calculator_115() =
        capture("calculator-1.15x", FONT_SCALE_PHONE) { Calculator(CalculatorPreviews.working) }

    @Test fun calculatorEmpty_1x() = capture("calculator-empty-1x", 1f) { Calculator(CalculatorPreviews.empty) }

    @Test fun calculatorDivideByZero_1x() =
        capture("calculator-divide-by-zero-1x", 1f) { Calculator(CalculatorPreviews.divideByZero) }

    @Test fun converter_1x() = capture("converter-1x", 1f) { Converter(ConverterPreviews.ecb) }

    @Config(qualifiers = PHONE_2X)
    @Test fun converter_2x() = capture("converter-2x", 2f) { Converter(ConverterPreviews.ecb) }

    @Test fun converter_rtl_1x() =
        capture("converter-rtl-1x", 1f, LayoutDirection.Rtl) { Converter(ConverterPreviews.ecb) }

    @Test fun converter_115() = capture("converter-1.15x", FONT_SCALE_PHONE) { Converter(ConverterPreviews.ecb) }

    @Test fun converterNoTable_1x() = capture("converter-no-table-1x", 1f) { Converter(ConverterPreviews.noTable) }

    @Test fun converterUnpublished_1x() =
        capture("converter-unpublished-1x", 1f) { Converter(ConverterPreviews.unpublished) }

    private companion object {
        /** The owner's phone runs text at 1.15. */
        const val FONT_SCALE_PHONE = 1.15f
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilitiesGoldensDarkTest : UtilitiesGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
