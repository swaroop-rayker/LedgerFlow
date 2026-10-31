package com.ledgerflow.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.usecase.SeedDefaultTaxonomyUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryKitRepository
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG66: a setup that failed said "You're set up" over the failure, and its only
 * control was "Dismiss" -- which cleared the message and left a step with
 * nothing to press. The message promised "try again" and nothing could.
 *
 * Now the step says it did not finish, and "Try again" runs the same setup: the
 * same words, currency and folder, so the user is asked for nothing new.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug66_SetupFailureOffersRetryTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vault: FakeVaultRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aFailedSetup_isNotCalledSetUp_andOffersTryAgain() {
        var retried = 0
        rule.setContent {
            LfTheme {
                OnboardingScreen(
                    state = OnboardingUiState(step = OnboardingStep.Complete, setupFailed = true),
                    onEvent = { if (it == OnboardingEvent.SetupRetried) retried++ },
                    onGeneratePhrase = {},
                )
            }
        }
        rule.onNodeWithText("You're set up").assertDoesNotExist()
        rule.onNodeWithText("Couldn't finish setting up").assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test
    fun tryAgain_runsTheSameSetup_andCanSucceed() {
        val vm = failedSetup()
        val first = vault.initializeRequests.single()

        vault.initializeResult = VaultOutcome.Unlocked
        vm.onEvent(OnboardingEvent.SetupRetried)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.initializeRequests).hasSize(2)
        val second = vault.initializeRequests.last()
        assertThat(second.mnemonic).isEqualTo(first.mnemonic)
        assertThat(second.baseCurrency).isEqualTo(first.baseCurrency)
        assertThat(second.backupTreeUri).isEqualTo(TREE)
        assertThat(vm.state.value.setupFailed).isFalse()
    }

    @Test
    fun aSecondFailure_offersTryAgainAgain() {
        val vm = failedSetup()

        vm.onEvent(OnboardingEvent.SetupRetried)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.initializeRequests).hasSize(2)
        assertThat(vm.state.value.setupFailed).isTrue()
        assertThat(vm.state.value.mnemonic).hasSize(PHRASE_WORDS)
    }

    /** Only a failed setup is retried: never one in flight, never one that worked. */
    @Test
    fun tryAgain_doesNothingUnlessTheSetupFailed() {
        val vm = failedSetup()
        vault.initializeResult = VaultOutcome.Unlocked
        vm.onEvent(OnboardingEvent.SetupRetried)
        vm.onEvent(OnboardingEvent.SetupRetried) // a double tap, while the first runs
        dispatcher.scheduler.advanceUntilIdle()
        vm.onEvent(OnboardingEvent.SetupRetried) // after it succeeded
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.initializeRequests).hasSize(2)
    }

    private fun failedSetup(): OnboardingViewModel {
        vault = FakeVaultRepository().also {
            it.initializeResult = VaultOutcome.Failed(RecoveryReason.DatabaseUnopenable)
        }
        val vm = OnboardingViewModel(
            initializeVault = InitializeVaultUseCase(
                vault = vault,
                seedDefaultTaxonomy = SeedDefaultTaxonomyUseCase(
                    FakeCategoryRepository(),
                    FakePaymentMethodRepository(),
                ),
            ),
            recoveryKit = FakeRecoveryKitRepository(),
            random = SecureRandom(),
            challengeRandom = Random(SEED),
            io = dispatcher,
        )
        vm.generatePhraseAndContinue()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onEvent(OnboardingEvent.PhraseAcknowledged)
        val mnemonic = vm.state.value.mnemonic
        vm.state.value.challengePositions.forEachIndexed { index, position ->
            vm.onEvent(OnboardingEvent.ChallengeAnswerChanged(index, mnemonic[position - 1]))
        }
        vm.onEvent(OnboardingEvent.ChallengeSubmitted)
        vm.onEvent(OnboardingEvent.RecoveryKitDismissed)
        vm.onEvent(OnboardingEvent.BackupLocationGranted(TREE))
        dispatcher.scheduler.advanceUntilIdle()
        check(vm.state.value.setupFailed) { "the setup should have failed" }
        return vm
    }

    private companion object {
        const val TREE = "content://tree/backups"
        const val PHRASE_WORDS = 24
        const val SEED = 42
    }
}
