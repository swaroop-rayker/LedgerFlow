package com.ledgerflow.feature.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.testing.screenshot.MIN_TOUCH_TARGET_DP
import com.ledgerflow.feature.onboarding.restore.RestoreScreen
import com.ledgerflow.feature.onboarding.restore.RestoreUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG43: two first-run choices were rows too small to choose from safely.**
 *
 * Onboarding's base-currency list gave each currency a 40 dp row, flush
 * against the next; restore's list of backups gave each a 24 dp row with 4 dp
 * between them, directly above two buttons. Both rows carry a `RadioButton`
 * with `onClick = null` — right for TalkBack, which then reads the row once —
 * and that drops Material's 48 dp minimum, so the row shrank to its content.
 *
 * The cost is not symmetric with an ordinary mis-tap: the base currency is
 * fixed once chosen (CLAUDE.md §0), and the backup chosen is the one restored.
 * Found by P5 step 4's touch-target check at font scale 1.0.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1800dp")
class Bug43_FirstRunChoiceRowsAreFullSizeTargetsTest {

    @get:Rule
    val rule = createComposeRule()

    /** Every tappable node whose label contains one of [labels] is at least 48 dp tall. */
    private fun assertRowsAreFullHeight(labels: List<String>) {
        val rows = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
            labels.any { it in text }
        }
        assertWithMessage("rows found").that(rows.size).isEqualTo(labels.size)
        rows.forEach { row ->
            val heightDp = with(rule.density) { row.size.height.toDp().value }
            assertWithMessage("%s height (dp)", row.config.getOrNull(SemanticsProperties.Text))
                .that(heightDp).isAtLeast(MIN_TOUCH_TARGET_DP.toFloat())
        }
    }

    @Test
    fun Bug43_everyBaseCurrencyRow_isAFullSizeTarget() {
        rule.setContent {
            LfTheme { OnboardingScreen(state = OnboardingUiState(), onEvent = {}, onGeneratePhrase = {}) }
        }

        assertRowsAreFullHeight(SupportedCurrencies.map { it.displayName })
    }

    @Test
    fun Bug43_everyBackupRow_isAFullSizeTarget() {
        val backups = listOf("ledgerflow-20260918-101500.lfbk", "ledgerflow-20260911-093000.lfbk")
        rule.setContent {
            LfTheme {
                RestoreScreen(
                    state = RestoreUiState(
                        treeUri = "content://tree",
                        backups = backups,
                        selectedBackup = backups.first(),
                        entry = PhraseEntry(requiredWordCount = 24),
                    ),
                    resuming = false,
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        // The rows read as dates; these are the two this fixture's names give.
        assertRowsAreFullHeight(listOf("Sep 18, 2026", "Sep 11, 2026"))
    }
}
