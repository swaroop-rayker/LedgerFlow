package com.ledgerflow.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.UpgradeBlockReason
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import com.ledgerflow.feature.onboarding.notifications.NotificationAccessScreen
import com.ledgerflow.feature.onboarding.notifications.NotificationAccessUiState
import com.ledgerflow.feature.onboarding.recovery.RecoveryFailure
import com.ledgerflow.feature.onboarding.recovery.RecoveryScreen
import com.ledgerflow.feature.onboarding.recovery.RecoveryUiState
import com.ledgerflow.feature.onboarding.restore.RestoreScreen
import com.ledgerflow.feature.onboarding.restore.RestoreUiState
import com.ledgerflow.feature.onboarding.upgrade.UpgradeBlockedScreen
import com.ledgerflow.feature.onboarding.upgrade.UpgradingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first-run and recovery screens at font scale 1.0 and 2.0 (§12, P5
 * step 4), each with §9.6's checks: onboarding's currency step, the phrase
 * reveal and the word challenge; notification access; Recovery with words
 * entered and a mismatch; restore choosing a backup; and the upgrade screens.
 *
 * **Only the public BIP-39 test vector's word appears** ("abandon"). A real
 * phrase never goes in a fixture or a golden.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class FirstRunScreensGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun onboarding(name: String, state: OnboardingUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { OnboardingScreen(state = state, onEvent = {}, onGeneratePhrase = {}) }
        }

    private val phrase = OnboardingUiState(
        step = OnboardingStep.PhraseDisplay,
        mnemonic = List(24) { "abandon" },
        phraseRevealed = true,
    )

    private val challenge = OnboardingUiState(
        step = OnboardingStep.WordChallenge,
        challengePositions = listOf(3, 11, 19),
        challengeError = true,
    )

    private val notificationsOff = NotificationAccessUiState(
        listenerGranted = false,
        postNotificationsGranted = true,
        postNotificationsApplicable = true,
        polled = true,
    )

    private val recovery = RecoveryUiState(
        reason = RecoveryReason.CanaryMismatch,
        entry = PhraseEntry(words = List(7) { "abandon" }, requiredWordCount = 24),
        failure = RecoveryFailure.PhraseDidNotMatch,
    )

    private val restore = RestoreUiState(
        treeUri = "content://tree",
        backups = listOf("ledgerflow-20260918-101500.lfbk", "ledgerflow-20260911-093000.lfbk"),
        selectedBackup = "ledgerflow-20260918-101500.lfbk",
        entry = PhraseEntry(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
    )

    private val lowStorage = UpgradeBlockReason.InsufficientStorage(
        requiredBytes = 42L * 1024 * 1024,
        availableBytes = 3L * 1024 * 1024,
    )

    @Test fun currency_1x() = onboarding("onboarding-currency-1x", OnboardingUiState(), 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun currency_2x() = onboarding("onboarding-currency-2x", OnboardingUiState(), 2f)

    @Test fun phrase_1x() = onboarding("onboarding-phrase-1x", phrase, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun phrase_2x() = onboarding("onboarding-phrase-2x", phrase, 2f)

    @Test fun challenge_1x() = onboarding("onboarding-challenge-1x", challenge, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun challenge_2x() = onboarding("onboarding-challenge-2x", challenge, 2f)

    /** Kit step: its "Skip" is the longest control label in onboarding. */
    @Test fun kit_1x() = onboarding("onboarding-kit-1x", OnboardingUiState(step = OnboardingStep.RecoveryKit), 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun kit_2x() = onboarding("onboarding-kit-2x", OnboardingUiState(step = OnboardingStep.RecoveryKit), 2f)

    @Test
    fun notifications_1x() = rule.captureScreenGolden("notifications-off-1x", 1f) {
        LfTheme { NotificationAccessScreen(state = notificationsOff, onEvent = {}, doneLabel = "Not now") }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun notifications_2x() = rule.captureScreenGolden("notifications-off-2x", 2f) {
        LfTheme { NotificationAccessScreen(state = notificationsOff, onEvent = {}, doneLabel = "Not now") }
    }

    @Test
    fun recovery_1x() = rule.captureScreenGolden("recovery-mismatch-1x", 1f) {
        LfTheme { RecoveryScreen(state = recovery, onEvent = {}) }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun recovery_2x() = rule.captureScreenGolden("recovery-mismatch-2x", 2f) {
        LfTheme { RecoveryScreen(state = recovery, onEvent = {}) }
    }

    @Test
    fun restore_1x() = rule.captureScreenGolden("restore-choosing-1x", 1f) {
        LfTheme { RestoreScreen(state = restore, resuming = false, onEvent = {}, onBack = {}) }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun restore_2x() = rule.captureScreenGolden("restore-choosing-2x", 2f) {
        LfTheme { RestoreScreen(state = restore, resuming = false, onEvent = {}, onBack = {}) }
    }

    @Test
    fun upgrading_1x() = rule.captureScreenGolden("upgrading-1x", 1f) {
        LfTheme { UpgradingScreen(from = 10, to = 11) }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun upgrading_2x() = rule.captureScreenGolden("upgrading-2x", 2f) {
        LfTheme { UpgradingScreen(from = 10, to = 11) }
    }

    @Test
    fun upgradeBlocked_1x() = rule.captureScreenGolden("upgrade-low-storage-1x", 1f) {
        LfTheme { UpgradeBlockedScreen(reason = lowStorage) }
    }

    @Config(qualifiers = PHONE_2X)
    @Test
    fun upgradeBlocked_2x() = rule.captureScreenGolden("upgrade-low-storage-2x", 2f) {
        LfTheme { UpgradeBlockedScreen(reason = lowStorage) }
    }
}
