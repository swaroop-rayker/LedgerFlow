package com.ledgerflow.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.BackupReminder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's backup reminder, rendered: what it says, that its action reaches the
 * Back up now screen, and that the action's label holds at font scale 2.0
 * (BUG9) — the owner's device runs above 1.0, and a reminder whose one button
 * breaks mid-word is one nobody taps.
 */
@RunWith(AndroidJUnit4::class)
class DashboardBackupReminderContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var backUpTaps = 0

    private fun show(reminder: BackupReminder?, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    DashboardScreen(
                        state = DashboardUiState(backupReminder = reminder),
                        onSetUpNotifications = {},
                        onBackUpNow = { backUpTaps++ },
                    )
                }
            }
        }
    }

    @Test
    fun noBackup_saysTheDataIsOnlyHere_andOffersABackup() {
        show(BackupReminder.NeverBackedUp)

        composeRule.onNodeWithText("No backup yet. Your data exists only on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Back up now").performClick()
        assertThat(backUpTaps).isEqualTo(1)
    }

    @Test
    fun aStaleBackup_namesItsAge() {
        show(BackupReminder.Stale(daysAgo = 12))

        composeRule.onNodeWithText("Last backup 12 days ago. Anything since is only on this phone.")
            .assertIsDisplayed()
    }

    @Test
    fun noReminder_rendersNothing() {
        show(reminder = null)

        composeRule.onAllNodesWithText("Back up now").assertCountEquals(0)
    }

    /**
     * BUG9: the action's label is one line at 2.0, and every character of it is
     * visible — measured the way `Bug9_ControlLabelsNeverWrapTest` measures.
     * Not `hasVisualOverflow`: with `softWrap = false` Compose reports it true
     * for a label painted in full (that test's KDoc), and it did so here.
     */
    @Test
    fun atFontScaleTwo_theActionsLabelIsWholeOnOneLine() {
        val label = "Back up now"
        show(BackupReminder.Stale(daysAgo = 12), fontScale = 2f)

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label).assertIsDisplayed()
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action?.invoke(layouts)
        val layout = layouts.single()
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.getLineEnd(0, visibleEnd = true)).isEqualTo(label.length)
    }
}
