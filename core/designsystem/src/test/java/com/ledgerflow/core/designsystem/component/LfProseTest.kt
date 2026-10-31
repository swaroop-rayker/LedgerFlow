package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.format.ProseMarkdown
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfTheme
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
 * `LfProse` and `LfExpandableRow` (Settings > About, V2-3): goldens at 1x, 2x
 * and RTL in both themes, and what a screen reader is told.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfProseTest {

    @get:Rule
    val rule = createComposeRule()

    private val sample = ProseMarkdown.parse(
        """
        ## What stays on your phone

        Everything you put into LedgerFlow stays on your phone. The database is
        **encrypted**, and backups use your `24-word` phrase.

        - your entries, amounts, merchants and budgets;
        - receipt photos and the text read from them;
        - your backups and exports.

        ## Children

        LedgerFlow is not directed at children under 13.
        """.trimIndent(),
    )

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { content() } }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        LfScaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.space.screenEdge),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap),
            ) { content() }
        }
    }

    @Composable
    private fun Faq(openFirst: Boolean) {
        LfPanel {
            LfExpandableRow(title = "Where is my data kept?", expanded = openFirst, onToggle = {}) {
                LfProse(
                    ProseMarkdown.parse("On this phone, encrypted. LedgerFlow has no server."),
                    tone = LfProseTone.Secondary,
                )
            }
            LfDivider()
            LfExpandableRow(title = "What happens if I lose my recovery phrase?", expanded = false, onToggle = {}) {}
        }
    }

    @Test fun prose_1x() = capture("prose-1x") { Page { LfProse(sample) } }

    @Config(qualifiers = PHONE_2X)
    @Test fun prose_2x() = capture("prose-2x", fontScale = 2f) { Page { LfProse(sample) } }

    @Test fun prose_1x_rtl() = capture("prose-1x-rtl", direction = LayoutDirection.Rtl) { Page { LfProse(sample) } }

    @Test fun expandable_1x() = capture("expandable-row-1x") { Page { Faq(openFirst = true) } }

    @Config(qualifiers = PHONE_2X)
    @Test fun expandable_2x() = capture("expandable-row-2x", fontScale = 2f) { Page { Faq(openFirst = true) } }

    @Test fun expandable_1x_rtl() =
        capture("expandable-row-1x-rtl", direction = LayoutDirection.Rtl) { Page { Faq(openFirst = true) } }

    /** A long policy can be read heading by heading. */
    @Test
    fun headings_areHeadingsToTalkBack() {
        rule.setContent { LfTheme { LfProse(sample) } }

        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(2)
    }

    /** Closed, the question says so and offers to open; one tap shows the answer. */
    @Test
    fun anExpandableRow_saysItsState_andOpensOnATap() {
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            LfTheme {
                LfExpandableRow(title = "Question", expanded = open, onToggle = { open = !open }) {
                    LfProse(ProseMarkdown.parse("The answer."))
                }
            }
        }
        val row = rule.onNodeWithText("Question")
        assertThat(row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
            .isEqualTo("Collapsed")
        assertThat(row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("Expand")
        rule.onNodeWithText("The answer.").assertDoesNotExist()

        row.performClick()

        rule.onNodeWithText("The answer.").assertExists()
        assertThat(row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
            .isEqualTo("Expanded")
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfProseDarkTest : LfProseTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
