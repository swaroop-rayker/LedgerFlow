package com.ledgerflow.feature.settings.allowlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.InstalledApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Add an app's search (owner, 2026-10-30): a phone can carry hundreds of
 * apps, and the picker listed every one with no way to narrow it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AllowlistSearchTest {

    @get:Rule
    val rule = createComposeRule()

    private val phonePe = InstalledApp(
        packageName = "com.phonepe.app",
        label = "PhonePe",
        carriesPersonalMessages = false,
    )

    @Test
    fun anAppMatches_byNameOrPackage_ignoringCaseAndSpaces() {
        assertThat(phonePe.matches("")).isTrue()
        assertThat(phonePe.matches("phone")).isTrue()
        assertThat(phonePe.matches("  PHONEPE ")).isTrue()
        assertThat(phonePe.matches("com.phonepe")).isTrue()
        assertThat(phonePe.matches("whatsapp")).isFalse()
    }

    @Test
    fun thePicker_hasTheSearch_andSaysWhenNothingMatches() {
        rule.setContent {
            LfTheme {
                AllowlistScreen(
                    state = AllowlistUiState(
                        isLoaded = true,
                        picking = true,
                        installed = emptyList(),
                        installedRead = true,
                        query = "zzz",
                    ),
                    onEvent = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Search apps").assertExists()
        rule.onNodeWithText("No app matches", substring = true).assertExists()
    }

    /** The list itself has no search: it is a handful of apps, all shown. */
    @Test
    fun theListItself_hasNoSearch() {
        rule.setContent {
            LfTheme {
                AllowlistScreen(state = AllowlistUiState(isLoaded = true), onEvent = {}, onBack = {})
            }
        }
        rule.onNodeWithText("Search apps").assertDoesNotExist()
    }
}
