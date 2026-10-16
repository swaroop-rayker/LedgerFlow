package com.ledgerflow.feature.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG54 — Home said "Nothing here yet" with entries in the vault.**
 *
 * Seen on the owner's Home and on LF Bench (2,005 entries, several that day),
 * and recorded as V2-PLAN BUG-C. Not a data bug: `DashboardScreen` drew its
 * empty state unconditionally after the banners, because Home's content was
 * deferred to "when P3 lands" and never revisited after P3 built the rollups.
 *
 * Both halves are asserted, because each alone passes a broken screen: "a vault
 * with entries hides it" passes a screen that never shows it, and "an empty
 * vault shows it" passes the screen this bug was.
 *
 * Composed rather than read off the state, because the defect was in the
 * screen: the state had nothing to say, and the screen said something anyway.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE, or text measures zero wide and nothing about layout can be trusted.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp")
class Bug54_HomeSummarisesWhenEntriesExistTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(state: DashboardUiState) {
        rule.setContent { LfTheme { DashboardScreen(state = state, onSetUpNotifications = {}) } }
    }

    @Test
    fun aVaultWithEntries_neverShowsTheEmptyState() {
        show(HomeFixtures.populated)

        rule.onNodeWithText(EMPTY_TITLE).assertDoesNotExist()
        rule.onNodeWithText("Recent spending").assertExists()
        rule.onNodeWithContentDescription("Spent this month", substring = true).assertExists()
        rule.onNodeWithContentDescription("Received this month", substring = true).assertExists()
    }

    /** Income alone is a vault with entries: "nothing here" would be false. */
    @Test
    fun onlyIncome_isNotEmpty() {
        show(base.copy(recentSpending = emptyList(), recentIncome = HomeFixtures.income))

        rule.onNodeWithText(EMPTY_TITLE).assertDoesNotExist()
        rule.onNodeWithText("Recent income").assertExists()
        // An empty book's list is hidden rather than shown with no rows.
        rule.onNodeWithText("Recent spending").assertDoesNotExist()
    }

    @Test
    fun anEmptyVault_alwaysShowsTheEmptyState() {
        show(base.copy(recentSpending = emptyList(), recentIncome = emptyList()))

        rule.onNodeWithText(EMPTY_TITLE).assertExists()
        rule.onNodeWithContentDescription("Spent this month", substring = true).assertDoesNotExist()
    }

    /**
     * Before either book has answered, neither claim is made: no empty state
     * flashed at a full vault, and no zeros.
     */
    @Test
    fun beforeTheBooksAnswer_neitherTheEmptyStateNorFiguresAreShown() {
        show(base)

        rule.onNodeWithText(EMPTY_TITLE).assertDoesNotExist()
        rule.onNodeWithContentDescription("Spent this month", substring = true).assertDoesNotExist()
    }

    private val base = DashboardUiState(captureHealth = NotificationCaptureHealth.CONNECTED)

    private companion object {
        const val EMPTY_TITLE = "Nothing here yet"
    }
}
