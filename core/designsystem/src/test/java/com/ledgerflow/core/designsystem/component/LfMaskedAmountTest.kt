package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.format.AmountMask
import com.ledgerflow.core.designsystem.format.BudgetWords
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `LfMaskedAmount` and `lfRevealAmounts` (Hide amounts on Home, V2-PLAN §3.5,
 * SPEC §9.6): the figure hidden from the screen *and* from the semantics tree,
 * one tap to show, and nothing added while shown.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfMaskedAmountTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { content() } }

    /** The same block shown and hidden, so a golden puts the two side by side. */
    @Composable
    private fun Specimen() {
        LfScaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.space.screenEdge),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap),
            ) {
                Block(hidden = false)
                Block(hidden = true)
            }
        }
    }

    @Composable
    private fun Block(hidden: Boolean) {
        LfPanel(modifier = Modifier.lfRevealAmounts(hidden = hidden, onReveal = {})) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LfTheme.space.cardInset),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
            ) {
                LfAdaptiveRow(
                    leading = { Text("Spent", style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
                    trailing = {
                        LfMaskedAmount(
                            amount = MoneyFormat.symbolised(BIG, INR),
                            hidden = hidden,
                            currencyCode = INR,
                            spoken = MoneyFormat.spoken(BIG, INR),
                            color = LfTheme.colors.money.outgoing,
                        )
                    },
                )
                LfAdaptiveRow(
                    leading = { Text("Received", style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
                    trailing = {
                        LfMaskedAmount(
                            amount = MoneyFormat.directional(SMALL, INR, LedgerType.CREDIT),
                            hidden = hidden,
                            currencyCode = INR,
                            spoken = MoneyFormat.spokenDirectional(SMALL, INR, LedgerType.CREDIT),
                            color = LfTheme.colors.money.incoming,
                            ledger = LedgerType.CREDIT,
                        )
                    },
                )
                Text(
                    text = if (hidden) {
                        BudgetWords.headroomHidden(over = false, currencyCode = INR)
                    } else {
                        MoneyFormat.symbolised(SMALL, INR) + " left of " + MoneyFormat.symbolised(BIG, INR)
                    },
                    style = LfTheme.type.label,
                    color = LfTheme.colors.text.secondary,
                )
            }
        }
    }

    @Test fun maskedAmount_1x() = capture("masked-amount-1x") { Specimen() }

    @Config(qualifiers = PHONE_2X)
    @Test fun maskedAmount_2x() = capture("masked-amount-2x", fontScale = 2f) { Specimen() }

    @Test fun maskedAmount_1x_rtl() = capture("masked-amount-1x-rtl", direction = LayoutDirection.Rtl) { Specimen() }

    /** The point of the component: while hidden, no node anywhere carries the figure. */
    @Test
    fun hidden_theFigureIsNowhereInTheSemanticsTree() {
        rule.setContent { LfTheme { Block(hidden = true) } }

        val said = rule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.everythingItSays() }
        assertThat(said).isNotEmpty()
        said.forEach { text ->
            assertThat(text).doesNotContain("12,34,567")
            assertThat(text).doesNotContain("250")
        }
        rule.onAllNodes(hasDescription(AmountMask.SPOKEN), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun shown_isTheFigure_spokenInWords() {
        rule.setContent { LfTheme { Block(hidden = false) } }

        rule.onAllNodes(hasDescription(MoneyFormat.spoken(BIG, INR)), useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodes(hasDescription(AmountMask.SPOKEN), useUnmergedTree = true).assertCountEquals(0)
    }

    /** One tap on the block reveals; a screen reader is offered the same action, by name. */
    @Test
    fun hidden_aTapShows_andTheActionIsNamed() {
        rule.setContent {
            var hidden by remember { mutableStateOf(true) }
            LfTheme {
                Column(modifier = Modifier.testTag(BLOCK).lfRevealAmounts(hidden) { hidden = false }) {
                    LfMaskedAmount(
                        amount = MoneyFormat.symbolised(BIG, INR),
                        hidden = hidden,
                        currencyCode = INR,
                        spoken = MoneyFormat.spoken(BIG, INR),
                    )
                }
            }
        }

        val action = rule.onNodeWithTag(BLOCK).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)
        assertThat(action?.label).isEqualTo(AmountMask.REVEAL_LABEL)

        rule.onNodeWithTag(BLOCK).performClick()

        rule.onAllNodes(hasDescription(MoneyFormat.spoken(BIG, INR)), useUnmergedTree = true).assertCountEquals(1)
        // Shown, the block is plain again: nothing left to tap.
        assertThat(rule.onNodeWithTag(BLOCK).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
            .isNull()
    }

    private fun hasDescription(text: String) = SemanticsMatcher("description is \"$text\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    private fun SemanticsNode.everythingItSays(): List<String> =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private companion object {
        const val INR = "INR"
        const val BIG = 12_34_567_00L
        const val SMALL = 250_00L
        const val BLOCK = "block"
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfMaskedAmountDarkTest : LfMaskedAmountTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
