package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.InitializeVaultUseCase
import com.ledgerflow.core.domain.usecase.SeedDefaultTaxonomyUseCase
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import com.ledgerflow.core.testing.vault.FakeRecoveryKitRepository
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BUG84: onboarding's "text file" Recovery Kit was saved as a PDF under a
 * `.txt` name, so a text viewer showed the PDF's bytes.
 *
 * Found by the owner on 2026-11-05. The format travels from the request
 * (`kitConfirmFormat`) to the picker (`kitPickerRequest`), and opening the
 * picker cleared both; by the time the picker returned a file the format was
 * gone, and the write fell back to PDF. The name was already `.txt`, chosen
 * from the request when the picker opened.
 *
 * `OnboardingViewModelTest`'s write test sends the file straight after the
 * request and never saw it: these drive the sequence the screen actually
 * sends — request, confirm, picker opened, file chosen.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Bug84_TextKitIsWrittenAsTextTest {

    private val dispatcher = StandardTestDispatcher()
    private val kit = FakeRecoveryKitRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun atRecoveryKit(): OnboardingViewModel {
        val vm = OnboardingViewModel(
            initializeVault = InitializeVaultUseCase(
                vault = FakeVaultRepository(),
                seedDefaultTaxonomy = SeedDefaultTaxonomyUseCase(
                    FakeCategoryRepository(),
                    FakePaymentMethodRepository(),
                ),
            ),
            recoveryKit = kit,
            random = SecureRandom(),
            challengeRandom = Random(42),
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
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.RecoveryKit)
        return vm
    }

    /** The screen's own sequence, with the name the picker was opened with. */
    private fun OnboardingViewModel.saveKit(format: RecoveryKitFormat) {
        onEvent(OnboardingEvent.RecoveryKitRequested(format))
        onEvent(OnboardingEvent.RecoveryKitConfirmed)
        val opened = requireNotNull(state.value.kitPickerRequest) { "the picker was not requested" }
        assertThat(opened).isEqualTo(format)
        onEvent(OnboardingEvent.RecoveryKitPickerLaunched)
        onEvent(OnboardingEvent.RecoveryKitFileChosen("content://docs/${suggestedKitFileName(opened)}"))
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun aTextKit_isWrittenAsText() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.saveKit(RecoveryKitFormat.Text)

        val (uri, format, _) = kit.written.single()
        assertThat(uri).endsWith(".txt")
        assertThat(format).isEqualTo(RecoveryKitFormat.Text)
        assertThat(vm.state.value.recoveryKitSaved).isTrue()
    }

    @Test
    fun aPdfKit_isStillWrittenAsAPdf() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.saveKit(RecoveryKitFormat.Pdf)

        val (uri, format, _) = kit.written.single()
        assertThat(uri).endsWith(".pdf")
        assertThat(format).isEqualTo(RecoveryKitFormat.Pdf)
    }

    /** The format is forgotten once the file is written, so the next request starts clean. */
    @Test
    fun theFormatIsForgotten_onceTheKitIsWritten() = runTest(dispatcher) {
        val vm = atRecoveryKit()

        vm.saveKit(RecoveryKitFormat.Text)

        assertThat(vm.state.value.kitConfirmFormat).isNull()
        assertThat(vm.state.value.kitPickerRequest).isNull()
        assertThat(vm.state.value.kitPendingFormat).isNull()
    }
}
