package com.ledgerflow

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.ObserveVaultStateUseCase
import com.ledgerflow.core.domain.usecase.OpenVaultOnLaunchUseCase
import com.ledgerflow.core.domain.usecase.PurgeAbandonedDraftsUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultState
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.vault.FakeVaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Routing the app shell off the vault (SPEC.md §7.3). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val drafts = FakeDraftRepository()

    private fun viewModel(vault: FakeVaultRepository) = AppViewModel(
        observeVaultState = ObserveVaultStateUseCase(vault),
        openVaultOnLaunch = OpenVaultOnLaunchUseCase(vault),
        purgeAbandonedDrafts = PurgeAbandonedDraftsUseCase(drafts),
    )

    /**
     * `route` is a `stateIn` over a `scan`, so it starts at [AppRoute.Loading]
     * and reaches its mapped value only once the scope runs. Every assertion
     * here settles the scheduler first and then reads the state, rather than
     * asserting on the first emission -- which would be the seed, not a routing
     * decision.
     */
    private fun routeOf(vault: FakeVaultRepository): AppRoute {
        val subject = viewModel(vault)
        // Collection has to be live for stateIn(WhileSubscribed) to run the scan.
        val job = CoroutineScope(dispatcher).launch { subject.route.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return subject.route.value.also { job.cancel() }
    }

    @Test
    fun launch_attemptsTheSilentUnlockExactlyOnce() = runTest(dispatcher) {
        val vault = FakeVaultRepository(VaultState.Initializing)

        viewModel(vault)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vault.openOnLaunchCalls).isEqualTo(1)
    }

    @Test
    fun noPhraseWrap_routesToOnboarding() = runTest(dispatcher) {
        assertThat(routeOf(FakeVaultRepository(VaultState.NeedsOnboarding)))
            .isEqualTo(AppRoute.Onboarding)
    }

    @Test
    fun keystoreFailure_routesToRecoveryCarryingTheReason() = runTest(dispatcher) {
        val vault = FakeVaultRepository(VaultState.NeedsRecovery(RecoveryReason.CanaryMismatch))

        assertThat(routeOf(vault)).isEqualTo(AppRoute.Recovery(RecoveryReason.CanaryMismatch))
    }

    @Test
    fun unlocked_routesToReady() {
        assertThat(routeOf(FakeVaultRepository(VaultState.Unlocked))).isEqualTo(AppRoute.Ready)
    }

    /**
     * `Working` occurs *inside* onboarding and *inside* recovery, both of which
     * own words the user is part-way through. Mapping it to a spinner would
     * blank the Recovery screen mid-KDF and throw away 24 typed words on the way
     * back. The previous route has to survive it.
     */
    @Test
    fun working_holdsThePreviousRouteRatherThanBlankingTheScreen() = runTest(dispatcher) {
        val vault = FakeVaultRepository(VaultState.NeedsRecovery(RecoveryReason.KeystoreUnavailable))
        val subject = viewModel(vault)
        val job = launch { subject.route.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(subject.route.value)
            .isEqualTo(AppRoute.Recovery(RecoveryReason.KeystoreUnavailable))

        vault.emit(VaultState.Working)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(subject.route.value)
            .isEqualTo(AppRoute.Recovery(RecoveryReason.KeystoreUnavailable))

        vault.emit(VaultState.Unlocked)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(subject.route.value).isEqualTo(AppRoute.Ready)

        job.cancel()
    }

    // ── The draft orphan sweep (SPEC.md §6.1.2) ─────────────────────────────

    /**
     * There is no database before the unlock succeeds, so a sweep that ran at
     * construction would throw on the one launch it matters for — a first run,
     * where `openOnLaunch` routes to onboarding and no vault exists at all.
     */
    @Test
    fun purge_doesNotRunBeforeTheVaultOpens() = runTest(dispatcher) {
        routeOf(FakeVaultRepository(VaultState.NeedsOnboarding))

        assertThat(drafts.purgeCalls).isEqualTo(0)
    }

    @Test
    fun purge_runsOnceTheVaultIsOpen() = runTest(dispatcher) {
        routeOf(FakeVaultRepository(VaultState.Unlocked))

        assertThat(drafts.purgeCalls).isEqualTo(1)
    }

    /** A user who came in through Recovery gets the same housekeeping. */
    @Test
    fun purge_runsAfterRecoveryToo() = runTest(dispatcher) {
        val vault = FakeVaultRepository(VaultState.NeedsRecovery(RecoveryReason.CanaryMismatch))
        val subject = viewModel(vault)
        val job = launch { subject.route.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(drafts.purgeCalls).isEqualTo(0)

        vault.emit(VaultState.Unlocked)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(drafts.purgeCalls).isEqualTo(1)
        job.cancel()
    }

    /** One sweep per process. `first` cancels the collection rather than subscribing. */
    @Test
    fun purge_runsOnceEvenIfTheVaultReopens() = runTest(dispatcher) {
        val vault = FakeVaultRepository(VaultState.Unlocked)
        val subject = viewModel(vault)
        val job = launch { subject.route.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        vault.emit(VaultState.Working)
        vault.emit(VaultState.Unlocked)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(drafts.purgeCalls).isEqualTo(1)
        job.cancel()
    }
}
