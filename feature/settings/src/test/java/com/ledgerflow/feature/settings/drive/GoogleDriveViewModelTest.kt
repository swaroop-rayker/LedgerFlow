package com.ledgerflow.feature.settings.drive

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveOutcome
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.drive.DriveUnavailable
import com.ledgerflow.core.domain.drive.ObserveDriveStatusUseCase
import com.ledgerflow.core.domain.drive.SetDriveEnabledUseCase
import com.ledgerflow.core.drive.DriveSignIn
import com.ledgerflow.core.drive.DriveSignInStep
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Turning Drive backup on and off (ADR-0034 §6): only a granted answer turns
 * the switch on, and then the first copy is queued at once; a declined or
 * unavailable sign-in leaves it off and says why; turning off asks first,
 * revokes the grant and leaves Drive's files alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [GOLDEN_SDK])
class GoogleDriveViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore()
    private val signIn = FakeSignIn()
    private var queued = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): GoogleDriveViewModel {
        val vm = GoogleDriveViewModel(
            observeStatus = ObserveDriveStatusUseCase(store),
            setEnabled = SetDriveEnabledUseCase(store),
            signIn = signIn,
            copies = { queued++ },
        )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    @Test
    fun aGrantedSignIn_turnsItOn_andQueuesTheFirstCopy() = runTest(dispatcher) {
        signIn.next = DriveSignInStep.Granted("me@example.com")
        val vm = viewModel()

        vm.onEvent(GoogleDriveEvent.TurnOn)
        advanceUntilIdle()

        assertThat(vm.state.value.status.enabled).isTrue()
        assertThat(queued).isEqualTo(1)
        assertThat(vm.state.value.message).startsWith("Drive backup is on")
    }

    @Test
    fun consent_isLaunchedOnce_andItsAnswerDecides() = runTest(dispatcher) {
        val intent = PendingIntent.getActivity(
            RuntimeEnvironment.getApplication(),
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        signIn.next = DriveSignInStep.NeedsConsent(intent)
        val vm = viewModel()

        vm.onEvent(GoogleDriveEvent.TurnOn)
        advanceUntilIdle()
        assertThat(vm.state.value.consent).isSameInstanceAs(intent)
        assertThat(store.enabled).isFalse()

        vm.onEvent(GoogleDriveEvent.ConsentLaunched)
        signIn.completion = DriveSignInStep.Granted(null)
        vm.onEvent(GoogleDriveEvent.ConsentReturned(Intent()))
        advanceUntilIdle()

        assertThat(vm.state.value.consent).isNull()
        assertThat(store.enabled).isTrue()
    }

    @Test
    fun aDeclinedSignIn_leavesItOff() = runTest(dispatcher) {
        signIn.completion = DriveSignInStep.Declined
        val vm = viewModel()

        vm.onEvent(GoogleDriveEvent.ConsentReturned(null))
        advanceUntilIdle()

        assertThat(store.enabled).isFalse()
        assertThat(queued).isEqualTo(0)
        assertThat(vm.state.value.message).isEqualTo("Drive backup stays off.")
    }

    @Test
    fun aBuildWithNoOauthClient_saysSo_andStaysOff() = runTest(dispatcher) {
        signIn.next = DriveSignInStep.Unavailable(DriveUnavailable.NotConfigured)
        val vm = viewModel()

        vm.onEvent(GoogleDriveEvent.TurnOn)
        advanceUntilIdle()

        assertThat(store.enabled).isFalse()
        assertThat(vm.state.value.message).isEqualTo("Google sign-in isn't set up for this build.")
    }

    /** Turning off asks first, revokes the grant for the account it last used, and touches nothing on Drive. */
    @Test
    fun turningOff_asksFirst_thenRevokes() = runTest(dispatcher) {
        store.enabled = true
        store.attempt = DriveAttempt(1L, DriveOutcome.Copied(1, 0, 1, 0), "me@example.com")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(GoogleDriveEvent.TurnOffRequested)
        advanceUntilIdle()
        assertThat(vm.state.value.confirmingOff).isTrue()
        assertThat(store.enabled).isTrue()

        vm.onEvent(GoogleDriveEvent.TurnOffConfirmed)
        advanceUntilIdle()

        assertThat(store.enabled).isFalse()
        assertThat(signIn.revoked).containsExactly("me@example.com")
        assertThat(vm.state.value.message).contains("still there")
    }

    @Test
    fun copyNow_queuesACopy() = runTest(dispatcher) {
        store.enabled = true
        val vm = viewModel()

        vm.onEvent(GoogleDriveEvent.CopyNow)

        assertThat(queued).isEqualTo(1)
    }

    @Test
    fun aGrantThatNeedsConsentAgain_offersSignIn() = runTest(dispatcher) {
        store.enabled = true
        store.attempt = DriveAttempt(1L, DriveOutcome.NeedsSignIn, null)
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.needsSignIn).isTrue()
        assertThat(driveSentence(vm.state.value.status)).startsWith("Sign in again")
    }

    private class FakeStore : DriveStatusStore {
        private val state = MutableStateFlow(Triple(false, null as DriveAttempt?, null as Long?))
        var enabled: Boolean
            get() = state.value.first
            set(value) {
                state.value = state.value.copy(first = value)
            }
        var attempt: DriveAttempt?
            get() = state.value.second
            set(value) {
                state.value = state.value.copy(second = value)
            }

        override fun observe(): Flow<DriveStatus> = state.map { (on, last, copied) -> DriveStatus(on, last, copied) }
        override suspend fun enabled() = enabled
        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
        override suspend fun record(attempt: DriveAttempt) {
            this.attempt = attempt
        }
    }

    private class FakeSignIn : DriveSignIn {
        var next: DriveSignInStep = DriveSignInStep.Declined
        var completion: DriveSignInStep = DriveSignInStep.Declined
        val revoked = mutableListOf<String?>()
        override suspend fun begin() = next
        override fun complete(data: Intent?) = completion
        override suspend fun revoke(account: String?) {
            revoked += account
        }
    }
}
