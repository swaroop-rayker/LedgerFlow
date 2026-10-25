package com.ledgerflow.feature.settings.allowlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.InstalledApp
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The payment-apps editor's goldens (§12; V2-PLAN §3.4): the list at 1.0, 2.0
 * and in RTL, and the add-an-app list. A switched-off built-in and an added
 * app are in the list, because those are the rows that differ.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class AllowlistScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private val list = AllowlistUiState(
        isLoaded = true,
        apps = listOf(
            AllowlistedApp("com.google.android.apps.nbu.paisa.user", "Google Pay", enabled = true, builtIn = true),
            AllowlistedApp("com.snapwork.hdfc", "HDFC Bank", enabled = true, builtIn = true),
            AllowlistedApp("net.one97.paytm", "Paytm", enabled = false, builtIn = true),
            AllowlistedApp("com.phonepe.app", "PhonePe", enabled = true, builtIn = true),
            AllowlistedApp("com.example.mybank", "My Bank", enabled = true, builtIn = false),
        ),
    )

    private val picker = list.copy(
        picking = true,
        installed = listOf(
            InstalledApp("com.example.cards", "Card Manager", carriesPersonalMessages = false),
            InstalledApp("com.google.android.apps.messaging", "Messages", carriesPersonalMessages = true),
            InstalledApp("com.whatsapp", "WhatsApp", carriesPersonalMessages = true),
        ),
    )

    private fun capture(
        name: String,
        state: AllowlistUiState,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        LfTheme { AllowlistScreen(state = state, onEvent = {}, onBack = {}) }
    }

    @Test fun list_1x() = capture("payment-apps-1x", list, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun list_2x() = capture("payment-apps-2x", list, 2f)

    @Test fun list_rtl_1x() = capture("payment-apps-rtl-1x", list, 1f, LayoutDirection.Rtl)

    @Test fun picker_1x() = capture("payment-apps-picker-1x", picker, 1f)
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AllowlistScreenGoldensDarkTest : AllowlistScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
