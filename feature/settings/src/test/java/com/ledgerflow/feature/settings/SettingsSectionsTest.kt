package com.ledgerflow.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings' layout and wiring (V2-2, `docs/V2-PLAN.md` D7; one page per
 * section since design-system step D4).
 *
 * V2-2 moved More's eight rows under four headings. A move has two ways to go
 * wrong that no golden reports by name: a row that drops out (the screen still
 * renders, one thing shorter), and a row wired to its neighbour's callback
 * (every row still looks right). This pins the first as data and drives the
 * second through the real screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// PHONE_2X's height: each page is a lazy list (design-system step D2), so only
// what fits is composed, and a whole page has to fit for these checks.
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_2X)
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
        SettingsItem.LockAfter to "event ${SettingsEvent.LockAfterRequested}",
        SettingsItem.DataPermissions to "data permissions",
        SettingsItem.Diagnostics to "diagnostics",
        SettingsItem.WhatsNew to "about ${AboutLink.WhatsNew}",
        SettingsItem.Help to "about ${AboutLink.Help}",
        SettingsItem.ReportBug to "about ${AboutLink.ReportBug}",
        SettingsItem.PrivacyPolicy to "about ${AboutLink.PrivacyPolicy}",
        SettingsItem.Terms to "about ${AboutLink.Terms}",
        SettingsItem.Licences to "about ${AboutLink.Licences}",
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
            // V2-3, SPEC §7.6: after Data, before how the app looks.
            "Security & privacy" to listOf(
                SettingsItem.AppLock,
                SettingsItem.LockAfter,
                SettingsItem.BlockScreenshots,
                // V2-3 (V2-PLAN §3.5): what the screen shows, then what the app may reach.
                SettingsItem.HideAmounts,
                SettingsItem.DataPermissions,
            ),
            "Appearance" to listOf(SettingsItem.Theme, SettingsItem.Animations, SettingsItem.StartScreen),
            "Currency & region" to listOf(
                SettingsItem.BaseCurrency,
                SettingsItem.NumberGrouping,
                SettingsItem.DateOrder,
            ),
            "Tools" to listOf(SettingsItem.Diagnostics),
            // V2-3 (V2-PLAN §3.8): last, the version first.
            "About" to listOf(
                SettingsItem.AppVersion,
                SettingsItem.WhatsNew,
                SettingsItem.Help,
                SettingsItem.ReportBug,
                SettingsItem.PrivacyPolicy,
                SettingsItem.Terms,
                SettingsItem.Licences,
            ),
        ).inOrder()
    }

    @Test
    fun everyRow_hasASentence() {
        // subtitleFor is a table; a row added without an entry would throw here
        // rather than when the tab is drawn.
        val state = SettingsUiState(isLoaded = true)
        SettingsItem.entries.forEach { item -> assertThat(subtitleFor(item, state)).isNotEmpty() }
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
        val facts = listOf(SettingsItem.BaseCurrency, SettingsItem.AppVersion)
        val switches = SettingsItem.entries.filter { toggleFor(it, state) != null }
        assertThat(switches)
            .containsExactly(SettingsItem.AppLock, SettingsItem.BlockScreenshots, SettingsItem.HideAmounts)
        assertThat(navigatingRows.keys + choices + facts + switches).containsExactlyElementsIn(SettingsItem.entries)
        assertThat(navigatingRows.keys.intersect(choices.toSet())).isEmpty()
        assertThat(navigatingRows.keys.intersect(facts.toSet())).isEmpty()
        assertThat((navigatingRows.keys + choices + facts).intersect(switches.toSet())).isEmpty()
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
    fun everySection_hasItsOwnPage_inOrder() {
        // D4: each section is a page, and the index lists them in this order.
        assertThat(SettingsSections.map { it.page }).containsExactlyElementsIn(SettingsPage.entries).inOrder()
        SettingsPage.entries.forEach { assertThat(settingsPageNamed(it.name)).isEqualTo(it) }
        assertThat(settingsPageNamed("Settings")).isNull()
    }

    @Test
    fun everyRow_opensWhatItNames() {
        val opened = mutableListOf<String>()
        var page by mutableStateOf(SettingsPage.Notifications)
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = page,
                    state = SettingsUiState(isLoaded = true),
                    onBack = { opened += "back" },
                    onCategories = { opened += "categories" },
                    onBudgets = { opened += "budgets" },
                    onExport = { opened += "export" },
                    onDeletedEntries = { opened += "bin" },
                    onNotificationAccess = { opened += "notification access" },
                    onBackUp = { opened += "back up" },
                    onDiagnostics = { opened += "diagnostics" },
                    onBudgetAlerts = { opened += "budget alerts" },
                    onPaymentApps = { opened += "payment apps" },
                    onDataPermissions = { opened += "data permissions" },
                    onAbout = { opened += "about $it" },
                    onEvent = { event -> opened += "event $event" },
                )
            }
        }

        // Every page, every navigating row on it: each opens exactly its own.
        SettingsSections.forEach { section ->
            page = section.page
            rule.waitForIdle()
            section.items.filter { it in navigatingRows }.forEach { item ->
                opened.clear()
                rule.onNodeWithText(item.title).performScrollTo().performClick()
                rule.waitForIdle()
                assertWithMessage("tapping \"${item.title}\" on ${section.heading}")
                    .that(opened).containsExactly(navigatingRows.getValue(item))
            }
            opened.clear()
            rule.onNodeWithContentDescription("Back").performClick()
            assertWithMessage("Back on ${section.heading}").that(opened).containsExactly("back")
        }
    }

    @Test
    fun eachPage_isTitledWithItsSection_andShowsOnlyItsRows() {
        var page by mutableStateOf(SettingsPage.Notifications)
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = page,
                    state = SettingsUiState(isLoaded = true),
                    onBack = {},
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onBudgetAlerts = {},
                    onPaymentApps = {},
                    onDataPermissions = {},
                    onAbout = {},
                    onEvent = {},
                )
            }
        }

        SettingsSections.forEach { section ->
            page = section.page
            rule.waitForIdle()
            rule.onAllNodesWithText(section.heading).assertCountEquals(1)
            SettingsItem.entries.forEach { item ->
                val expected = if (item in section.items) 1 else 0
                rule.onAllNodesWithText(item.title).assertCountEquals(expected)
            }
        }
    }

    @Test
    fun index_listsItsSections_andOpensEachPage() {
        val opened = mutableListOf<SettingsPage>()
        rule.setContent {
            LfTheme {
                SettingsIndexScreen(state = SettingsUiState(isLoaded = true), onPage = { opened += it }, onBack = {})
            }
        }
        // Organise and Data are opened from Profile instead (BUG65).
        SettingsIndexSections.forEach { section ->
            opened.clear()
            rule.onNodeWithText(section.heading).performScrollTo().performClick()
            assertWithMessage("tapping \"${section.heading}\"").that(opened).containsExactly(section.page)
        }
    }
}
