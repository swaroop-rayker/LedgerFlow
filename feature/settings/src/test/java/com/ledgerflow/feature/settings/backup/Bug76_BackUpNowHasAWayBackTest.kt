package com.ledgerflow.feature.settings.backup

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG76: Back up now is a full-screen page with no bottom bar, and it drew no
 * Back or Done -- the system gesture was the only way out. It had "Done", and
 * since step F5 the back arrow beside its title, which is off while a backup
 * is being written.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug76_BackUpNowHasAWayBackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun done_leavesTheScreen() {
        var left = 0
        rule.setContent {
            LfTheme {
                BackupNowScreen(
                    state = BackupNowUiState(entry = PhraseEntry(requiredWordCount = 24), folderChecked = true),
                    onEvent = {},
                    onBack = { left++ },
                )
            }
        }
        rule.onNodeWithContentDescription("Back").performClick()
        assertThat(left).isEqualTo(1)
    }

    @Test
    fun done_waitsForABackupInProgress() {
        var left = 0
        rule.setContent {
            LfTheme {
                BackupNowScreen(
                    state = BackupNowUiState(
                        entry = PhraseEntry(requiredWordCount = 24),
                        folderChecked = true,
                        isWorking = true,
                    ),
                    onEvent = {},
                    onBack = { left++ },
                )
            }
        }
        rule.onNodeWithContentDescription("Back").assertIsNotEnabled().performClick()
        assertThat(left).isEqualTo(0)
    }
}
