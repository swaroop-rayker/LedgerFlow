package com.ledgerflow.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
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
 * The More tab's goldens at 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * Two states, chosen for what the rows *say*: a lived-in install (capture on,
 * a nightly backup, things in the bin and receipts kept) and a new one (capture
 * off, never backed up, nothing anywhere) — every subtitle has a different
 * sentence in each, and those sentences are the screen.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class MoreScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: MoreUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme {
                MoreScreen(
                    state = state,
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onEvent = {},
                )
            }
        }

    private val livedIn = MoreUiState(
        deletedCount = 3,
        isLoaded = true,
        receipts = AttachmentUsage(count = 12, bytes = 3_400_000L),
        captureHealth = NotificationCaptureHealth.CONNECTED,
        lastBackupAt = 1_790_000_000_000L,
        nightlyBackupsEnabled = true,
    )

    private val fresh = MoreUiState(
        deletedCount = 0,
        isLoaded = true,
        captureHealth = NotificationCaptureHealth.NOT_GRANTED,
    )

    @Test fun livedIn_1x() = capture("more-lived-in-1x", livedIn, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun livedIn_2x() = capture("more-lived-in-2x", livedIn, 2f)

    @Test fun fresh_1x() = capture("more-fresh-1x", fresh, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun fresh_2x() = capture("more-fresh-2x", fresh, 2f)
}
