package com.ledgerflow.feature.settings.backup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Back up now" at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * **Only the public BIP-39 test vector's words appear here** ("abandon"), the
 * ones every BIP-39 implementation publishes. A real phrase never goes in a
 * fixture, a golden, or anything else committed.
 *
 * - **No folder yet** — the first thing a backup asks, with a word part-typed.
 * - **Typing** — three committed words as chips, folder chosen.
 * - **Done** — the receipt, with a changed folder and the images counted.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class BackupNowScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: BackupNowUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { BackupNowScreen(state = state, onEvent = {}) }
        }

    private val needsFolder = BackupNowUiState(
        entry = PhraseEntry(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
        folderChecked = true,
    )

    private val typing = BackupNowUiState(
        entry = PhraseEntry(words = List(3) { "abandon" }, requiredWordCount = 24),
        folderName = "LedgerFlow backups",
        folderChecked = true,
        nightlyBackupsEnabled = true,
    )

    private val done = BackupNowUiState(
        entry = PhraseEntry(requiredWordCount = 24),
        folderName = "LedgerFlow backups",
        folderChecked = true,
        folderChanged = true,
        result = BackupOutcome.Done(
            fileName = "ledgerflow-20260918-101500.lfbk",
            rows = 412,
            imagesWritten = 3,
            imagesAlreadyThere = 9,
            imagesUnreadable = 0,
            imagesFailed = 0,
            olderBackupsRemoved = 1,
        ),
    )

    @Test fun needsFolder_1x() = capture("backup-needs-folder-1x", needsFolder, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun needsFolder_2x() = capture("backup-needs-folder-2x", needsFolder, 2f)

    @Test fun typing_1x() = capture("backup-typing-1x", typing, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun typing_2x() = capture("backup-typing-2x", typing, 2f)

    @Test fun done_1x() = capture("backup-done-1x", done, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun done_2x() = capture("backup-done-2x", done, 2f)
}
