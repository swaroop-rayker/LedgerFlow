package com.ledgerflow.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.usecase.SeedDefaultTaxonomyUseCase
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
 * BUG67: `OnboardingStep.BackupLocation` said a user may "decline with a
 * warning", and "Not now" declined with none -- straight on to creating a
 * vault that nothing backs up. Now "Not now" opens the warning, and only its
 * own "Not now" declines.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug67_NotNowWarnsBeforeSkippingBackupsTest {

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
    fun notNow_opensTheWarning_andDeclinesNothing() {
        val vm = atBackupLocation()

        vm.onEvent(OnboardingEvent.BackupLocationDeclineRequested)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.confirmingBackupDecline).isTrue()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.BackupLocation)
        assertThat(vault.initializeRequests).isEmpty()
    }

    @Test
    fun cancellingTheWarning_leavesTheStepUnanswered() {
        val vm = atBackupLocation()

        vm.onEvent(OnboardingEvent.BackupLocationDeclineRequested)
        vm.onEvent(OnboardingEvent.BackupLocationDeclineCancelled)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.confirmingBackupDecline).isFalse()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.BackupLocation)
        assertThat(vault.initializeRequests).isEmpty()
    }

    @Test
    fun acceptingTheWarning_declinesAndMovesOn() {
        val vm = atBackupLocation()

        vm.onEvent(OnboardingEvent.BackupLocationDeclineRequested)
        vm.onEvent(OnboardingEvent.BackupLocationDeclined)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Complete)
        assertThat(vm.state.value.confirmingBackupDecline).isFalse()
        assertThat(vault.initializeRequests.single().backupTreeUri).isNull()
    }

    @Test
    fun theStepsNotNow_asksFirst() {
        val events = mutableListOf<OnboardingEvent>()
        rule.setContent {
            LfTheme {
                OnboardingScreen(
                    state = OnboardingUiState(step = OnboardingStep.BackupLocation),
                    onEvent = { events += it },
                    onGeneratePhrase = {},
                )
            }
        }
        rule.onNodeWithText("Not now").performClick()
        assertThat(events).containsExactly(OnboardingEvent.BackupLocationDeclineRequested)
    }

    /** The step's own "Not now" asks; only the warning's declines; Cancel declines nothing. */
    @Test
    fun theWarning_saysWhatIsAtStake_andOnlyItsNotNowDeclines() {
        val events = mutableListOf<OnboardingEvent>()
        rule.setContent {
            LfTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.BackupLocation,
                        confirmingBackupDecline = true,
                    ),
                    onEvent = { events += it },
                    onGeneratePhrase = {},
                )
            }
        }
        rule.onNodeWithText("Skip nightly backups?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertThat(events).containsExactly(OnboardingEvent.BackupLocationDeclineCancelled)

        events.clear()
        val notNow = rule.onAllNodesWithText("Not now")
        repeat(notNow.fetchSemanticsNodes().size) { notNow[it].performClick() }
        assertThat(events).containsExactly(
            OnboardingEvent.BackupLocationDeclineRequested,
            OnboardingEvent.BackupLocationDeclined,
        )
    }

    private fun atBackupLocation(): OnboardingViewModel {
        vault = FakeVaultRepository()
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
        check(vm.state.value.step == OnboardingStep.BackupLocation)
        return vm
    }

    private companion object {
        const val SEED = 42
    }
}
