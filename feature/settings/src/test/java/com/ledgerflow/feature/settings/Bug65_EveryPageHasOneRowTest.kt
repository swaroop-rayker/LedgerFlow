package com.ledgerflow.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * **BUG65 — Export had two rows.**
 *
 * Found 2026-10-27 by the owner on the device, checking design-system step D4:
 * Profile had an "Export data" row and Settings > Data still had its Export
 * row, two ways to one screen on neighbouring pages. The owner's fix moves
 * Organise and Data out of Settings onto Profile and drops the extra row, so
 * Export is reached only through Data. Never in a release.
 *
 * Held as a rule rather than a one-off: every page is opened by exactly one
 * row, either on Profile or in the Settings index, and no row on Profile is
 * also a row on one of its pages.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class Bug65_EveryPageHasOneRowTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyPage_isOpenedFromProfile_orFromTheIndex_neverBoth() {
        val fromProfile = SettingsSections.filter { it.page in ProfilePages }.map { it.page }
        val fromIndex = SettingsIndexSections.map { it.page }
        assertThat(fromProfile).containsExactly(SettingsPage.Organise, SettingsPage.Data).inOrder()
        assertThat(fromProfile + fromIndex).containsExactlyElementsIn(SettingsPage.entries)
        assertThat(fromProfile.intersect(fromIndex.toSet())).isEmpty()
    }

    @Test
    fun profile_opensOrganiseDataAndSettings_andHasNoExportRowOfItsOwn() {
        val opened = mutableListOf<String>()
        rule.setContent {
            LfTheme { ProfileScreen(onPage = { opened += it.name }, onSettings = { opened += "settings" }) }
        }
        listOf("Organise" to "Organise", "Data" to "Data", "Settings" to "settings").forEach { (title, page) ->
            opened.clear()
            rule.onNodeWithText(title).performClick()
            assertWithMessage("tapping \"$title\"").that(opened).containsExactly(page)
        }
        // Export is a row on Data; a second one here was the bug.
        SettingsItem.entries.forEach { item -> rule.onAllNodesWithText(item.title).assertCountEquals(0) }
        rule.onAllNodesWithText("Export data").assertCountEquals(0)
    }

    @Test
    fun theIndex_hasNoRowForAPageProfileOpens() {
        rule.setContent {
            LfTheme { SettingsIndexScreen(state = SettingsUiState(isLoaded = true), onPage = {}, onBack = {}) }
        }
        rule.onAllNodesWithText("Organise").assertCountEquals(0)
        rule.onAllNodesWithText("Data").assertCountEquals(0)
        SettingsIndexSections.forEach { rule.onAllNodesWithText(it.heading).assertCountEquals(1) }
    }
}
