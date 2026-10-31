package com.ledgerflow.feature.dashboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.format.AmountMask
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hide amounts on Home (V2-PLAN §3.5, SPEC §9.6), on the real screen.
 *
 * A golden shows the dots; it cannot show what TalkBack, or any accessibility
 * service reading the window, still finds behind them. This reads every node
 * on a populated Home: hidden, no node may say a figure; shown, the same sweep
 * must find them, or the first half proves nothing.
 *
 * A figure is anything written to two decimals ("12,400.00", "1,240.50
 * rupees"). Home's other numbers -- dates, counts, the coverage percentage --
 * are whole, so they cannot trip it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall enough that the lazy list composes every card and both recent lists.
@Config(sdk = [34], qualifiers = "w360dp-h4000dp")
class HideAmountsHomeTest {

    @get:Rule
    val rule = createComposeRule()

    private var reveals = 0

    private fun show(state: DashboardUiState) {
        rule.setContent {
            LfTheme {
                DashboardScreen(state = state, onSetUpNotifications = {}, onRevealAmounts = { reveals++ })
            }
        }
        // The sweep below is only meaningful if the last section is there.
        rule.onNode(hasText("Recent income")).assertExists()
    }

    private fun everythingSaid(): List<String> =
        rule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    @Test
    fun hidden_noNodeSaysAFigure() {
        show(HomeFixtures.populated.copy(hideAmounts = true))

        val figures = everythingSaid().filter { FIGURE.containsMatchIn(it) }

        assertThat(figures).isEmpty()
        assertThat(everythingSaid().count { it.contains(AmountMask.SPOKEN) }).isAtLeast(1)
    }

    @Test
    fun shown_theSameSweepFindsTheFigures() {
        show(HomeFixtures.populated)

        assertThat(everythingSaid().count { FIGURE.containsMatchIn(it) }).isAtLeast(5)
        assertThat(everythingSaid().none { it.contains(AmountMask.SPOKEN) }).isTrue()
        assertThat(revealTargets().fetchSemanticsNodes()).isEmpty()
    }

    /**
     * Every block that hides a figure is a way to show them: the month, the
     * budgets' bars, the charges due and both recent lists. One tap is enough.
     */
    @Test
    fun hidden_everyHiddenBlockOffersShowAmounts_andATapAsksOnce() {
        show(HomeFixtures.populated.copy(hideAmounts = true))

        val targets = revealTargets().fetchSemanticsNodes()
        assertThat(targets).hasSize(REVEAL_BLOCKS)

        revealTargets()[0].performClick()
        assertThat(reveals).isEqualTo(1)

        revealTargets()[1].performSemanticsAction(SemanticsActions.OnClick)
        assertThat(reveals).isEqualTo(2)
    }

    /** Revealed, Home is Home as it always was: figures and no reveal targets. */
    @Test
    fun revealed_isTheOrdinaryHome() {
        show(HomeFixtures.populated.copy(hideAmounts = true, amountsRevealed = true))

        assertThat(everythingSaid().count { FIGURE.containsMatchIn(it) }).isAtLeast(5)
        assertThat(revealTargets().fetchSemanticsNodes()).isEmpty()
    }

    private fun revealTargets() = rule.onAllNodes(
        SemanticsMatcher("offers \"${AmountMask.REVEAL_LABEL}\"") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == AmountMask.REVEAL_LABEL
        },
    )

    private companion object {
        val FIGURE = Regex("""\d\.\d\d""")

        /** Month card, budgets' bars, charges due, recent spending, recent income. */
        const val REVEAL_BLOCKS = 5
    }
}
