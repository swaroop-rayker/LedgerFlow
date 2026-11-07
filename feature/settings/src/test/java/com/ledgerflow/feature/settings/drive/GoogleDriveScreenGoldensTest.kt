package com.ledgerflow.feature.settings.drive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveOutcome
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
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
 * Settings > Data > Google Drive (ADR-0034; owner's layout A): off, copied,
 * and asking to sign in again, at font scale 1.0 and 2.0, and copied from the
 * right.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class GoogleDriveScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private val off = GoogleDriveUiState()

    private val copied = GoogleDriveUiState(
        status = DriveStatus(
            enabled = true,
            lastAttempt = DriveAttempt(AT, DriveOutcome.Copied(1, 2, 3_100_000, 0), "swaroop@example.com"),
            lastCopiedAt = AT,
        ),
    )

    private val signInAgain = GoogleDriveUiState(
        status = DriveStatus(
            enabled = true,
            lastAttempt = DriveAttempt(AT, DriveOutcome.NeedsSignIn, "swaroop@example.com"),
            lastCopiedAt = AT - DAY * 4,
        ),
    )

    private fun capture(
        name: String,
        state: GoogleDriveUiState,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        LfTheme { GoogleDriveScreen(state = state, onEvent = {}, onBack = {}) }
    }

    @Test fun off_1x() = capture("drive-off-1x", off, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun off_2x() = capture("drive-off-2x", off, 2f)

    @Test fun copied_1x() = capture("drive-copied-1x", copied, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun copied_2x() = capture("drive-copied-2x", copied, 2f)

    @Test fun copiedRtl_1x() = capture("drive-copied-rtl-1x", copied, 1f, LayoutDirection.Rtl)

    @Test fun signInAgain_1x() = capture("drive-sign-in-again-1x", signInAgain, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun signInAgain_2x() = capture("drive-sign-in-again-2x", signInAgain, 2f)

    private companion object {
        /** 7 Nov 2026, 03:14 UTC. */
        const val AT = 1_794_021_240_000L
        const val DAY = 86_400_000L
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleDriveScreenGoldensDarkTest : GoogleDriveScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
