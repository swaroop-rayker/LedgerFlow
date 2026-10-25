package com.ledgerflow.feature.settings.allowlist

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.InstalledApp
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Notifications > Payment apps (SPEC §5.2, D-10; V2-PLAN §3.4):
 * the guarantee stated first, built-ins switch but never go, added apps can be
 * removed, and the warning names what adding a messaging app does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class AllowlistScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<AllowlistEvent>()

    private val list = AllowlistUiState(
        isLoaded = true,
        apps = listOf(
            AllowlistedApp("com.phonepe.app", "PhonePe", enabled = true, builtIn = true),
            AllowlistedApp("net.one97.paytm", "Paytm", enabled = false, builtIn = true),
            AllowlistedApp("com.example.mybank", "My Bank", enabled = true, builtIn = false),
        ),
    )

    private fun show(state: AllowlistUiState) {
        rule.setContent { LfTheme { AllowlistScreen(state = state, onEvent = { events += it }, onBack = {}) } }
    }

    @Test
    fun theGuarantee_isStatedFirst() {
        show(list)
        rule.onNodeWithText(ALLOWLIST_EXPLAINER).assertExists()
        assertThat(ALLOWLIST_EXPLAINER).contains("Nothing from any other app is read")
    }

    @Test
    fun eachSwitch_showsAndSendsItsApp() {
        show(list)
        rule.onNodeWithText("PhonePe").assertIsOn()
        rule.onNodeWithText("Paytm").assertIsOff()
        rule.onNodeWithText("Paytm").performClick()
        assertThat(events).containsExactly(AllowlistEvent.Toggled("net.one97.paytm", true))
    }

    @Test
    fun onlyAnAddedApp_hasRemove() {
        show(list)
        rule.onAllNodesWithText("Remove").assertCountEquals(1)
        rule.onNodeWithText("Remove").performClick()
        assertThat(events).containsExactly(AllowlistEvent.Removed("com.example.mybank"))
        assertThat(rowSupporting(list.apps[0]).withoutBreaks()).isEqualTo("Built in · com.phonepe.app")
        assertThat(rowSupporting(list.apps[2]).withoutBreaks()).isEqualTo("Added by you · com.example.mybank")
    }

    @Test
    fun addAnApp_opensThePicker_andAPickIsSent() {
        val whatsApp = InstalledApp("com.whatsapp", "WhatsApp", carriesPersonalMessages = true)
        show(list.copy(picking = true, installed = listOf(whatsApp)))
        rule.onNodeWithText("WhatsApp").performClick()
        assertThat(events).containsExactly(AllowlistEvent.Picked(whatsApp))
        assertThat(installedSupporting(whatsApp).withoutBreaks()).isEqualTo("com.whatsapp · personal messages")
    }

    @Test
    fun theWarning_namesWhatHappensNext() {
        val text = personalMessagesWarning("WhatsApp")
        assertThat(text).contains("read every notification it posts")
        assertThat(text).contains("90 days")
        assertThat(text).contains("in your Inbox to dismiss")
        assertThat(text).contains("may also arrive twice")
    }
}

/** The text as read, without the line-break opportunities after each dot. */
private fun String.withoutBreaks(): String = filterNot { it.code == 0x200B }
