package com.ledgerflow.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings tab's layout and wiring (V2-2, `docs/V2-PLAN.md` D7).
 *
 * V2-2 moved More's eight rows under four headings. A move has two ways to go
 * wrong that no golden reports by name: a row that drops out (the screen still
 * renders, one thing shorter), and a row wired to its neighbour's callback
 * (every row still looks right). This pins the first as data and drives the
 * second through the real screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class SettingsSectionsTest {

    @get:Rule
    val rule = createComposeRule()

    /** Every row that opens something, and what it opens. The rest are choices. */
    private val navigatingRows = mapOf(
        SettingsItem.NotificationCapture to "notification access",
        SettingsItem.PaymentApps to "payment apps",
        SettingsItem.BudgetAlerts to "budget alerts",
        SettingsItem.Categories to "categories",
        SettingsItem.Budgets to "budgets",
        SettingsItem.BackUpNow to "back up",
        SettingsItem.Export to "export",
        SettingsItem.DeletedEntries to "bin",
        // Opens the confirmation; deleting is the dialog's job (ADR-0023).
        SettingsItem.Receipts to "event ${SettingsEvent.ReceiptDeleteRequested}",
        // Opens its picker; picking is the dialog's job.
        SettingsItem.DateOrder to "event ${SettingsEvent.DateOrderRequested}",
        SettingsItem.Diagnostics to "diagnostics",
    )

    @Test
    fun sections_areTheOwnersSplit_inMoresOrder() {
        // Owner, 2026-10-21: every row More had is a setting, Diagnostics the
        // one tool. Within a section, the order More had.
        assertThat(SettingsSections.map { it.heading to it.items }).containsExactly(
            // V2-3 renamed Capture to Notifications (V2-PLAN §3.4): what LedgerFlow
            // reads, then what it sends.
            "Notifications" to listOf(
                SettingsItem.NotificationCapture,
                SettingsItem.PaymentApps,
                SettingsItem.BudgetAlerts,
            ),
            "Organise" to listOf(SettingsItem.Categories, SettingsItem.Budgets),
            "Data" to listOf(
                SettingsItem.BackUpNow,
                SettingsItem.Export,
                SettingsItem.DeletedEntries,
                SettingsItem.Receipts,
            ),
            // V2-3 (2026-10-24): below the sections people return to, above Tools.
            "Appearance" to listOf(SettingsItem.Theme, SettingsItem.Animations, SettingsItem.StartScreen),
            "Currency & region" to listOf(
                SettingsItem.BaseCurrency,
                SettingsItem.NumberGrouping,
                SettingsItem.DateOrder,
            ),
            "Tools" to listOf(SettingsItem.Diagnostics),
        ).inOrder()
    }

    @Test
    fun everyRow_navigates_isAChoice_orIsAFact_exactlyOne() {
        // A row that is two of these would be a choice whose tap also leaves,
        // or a fact that looks tappable. The base currency is the one fact (D4).
        val state = SettingsUiState(isLoaded = true)
        val choices = SettingsItem.entries.filter { choiceFor(it, state) != null }
        assertThat(choices).containsExactly(
            SettingsItem.Theme,
            SettingsItem.Animations,
            SettingsItem.StartScreen,
            SettingsItem.NumberGrouping,
        )
        val facts = listOf(SettingsItem.BaseCurrency)
        assertThat(navigatingRows.keys + choices + facts).containsExactlyElementsIn(SettingsItem.entries)
        assertThat(navigatingRows.keys.intersect(choices.toSet())).isEmpty()
        assertThat(navigatingRows.keys.intersect(facts.toSet())).isEmpty()
    }

    @Test
    fun everyRow_appearsExactlyOnce() {
        val drawn = SettingsSections.flatMap { it.items }
        assertThat(drawn).containsExactlyElementsIn(SettingsItem.entries)
        assertThat(drawn).containsNoDuplicates()
    }

    @Test
    fun backUpNow_staysDirectlyAboveExport() {
        // §16 Q23: the two are easy to confuse and must be read together.
        val data = SettingsSections.single { SettingsItem.Export in it.items }.items
        assertThat(data.indexOf(SettingsItem.BackUpNow))
            .isEqualTo(data.indexOf(SettingsItem.Export) - 1)
    }

    @Test
    fun everyRow_opensWhatItNames() {
        val opened = mutableListOf<String>()
        rule.setContent {
            LfTheme {
                SettingsScreen(
                    state = SettingsUiState(isLoaded = true),
                    onCategories = { opened += "categories" },
                    onBudgets = { opened += "budgets" },
                    onExport = { opened += "export" },
                    onDeletedEntries = { opened += "bin" },
                    onNotificationAccess = { opened += "notification access" },
                    onBackUp = { opened += "back up" },
                    onDiagnostics = { opened += "diagnostics" },
                    onBudgetAlerts = { opened += "budget alerts" },
                    onPaymentApps = { opened += "payment apps" },
                    onEvent = { event -> opened += "event $event" },
                )
            }
        }

        navigatingRows.forEach { (item, callback) ->
            opened.clear()
            rule.onNodeWithText(item.title).performScrollTo().performClick()
            rule.waitForIdle()
            assertWithMessage("tapping \"${item.title}\"").that(opened).containsExactly(callback)
        }
    }

    @Test
    fun eachSectionName_isAHeading_andNothingElseIs() {
        rule.setContent {
            LfTheme {
                SettingsScreen(
                    state = SettingsUiState(isLoaded = true),
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onBudgetAlerts = {},
                    onPaymentApps = {},
                    onEvent = {},
                )
            }
        }

        // TalkBack's "headings" navigation is how a screen-reader user skips a
        // section (§9.6), so each name must be one and no row may be.
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertThat(headings).containsExactlyElementsIn(SettingsSections.map { it.heading }).inOrder()
        SettingsSections.forEach { section ->
            rule.onAllNodesWithText(section.heading).assertCountEquals(1)
        }
    }
}
