package com.ledgerflow.feature.settings.allowlist

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.InstalledApp
import com.ledgerflow.core.domain.ingest.InstalledApps
import com.ledgerflow.core.domain.ingest.PackageAllowlistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The allowlist editor's decisions (V2-PLAN §3.4): an ordinary app is added on
 * the tap, an app that carries personal messages only after the warning, and
 * the picker never offers an app already on the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllowlistViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val listed = MutableStateFlow(
        listOf(AllowlistedApp("com.phonepe.app", "PhonePe", enabled = true, builtIn = true)),
    )
    private val added = mutableListOf<String>()

    private val repository = object : PackageAllowlistRepository {
        override fun observe(): Flow<List<AllowlistedApp>> = listed
        override suspend fun setEnabled(packageName: String, enabled: Boolean) = Unit
        override suspend fun add(packageName: String, label: String) {
            added += packageName
        }
        override suspend fun remove(packageName: String) = true
    }

    private val phone = object : InstalledApps {
        override suspend fun launchable() = listOf(
            InstalledApp("com.phonepe.app", "PhonePe", carriesPersonalMessages = false),
            InstalledApp("com.example.mybank", "My Bank", carriesPersonalMessages = false),
            InstalledApp("com.whatsapp", "WhatsApp", carriesPersonalMessages = true),
        )
    }

    private val whatsApp = InstalledApp("com.whatsapp", "WhatsApp", carriesPersonalMessages = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun thePicker_offersOnlyAppsNotOnTheList() = runTest(dispatcher) {
        val vm = AllowlistViewModel(repository, phone)
        backgroundScope.launch { vm.state.collect {} }
        vm.onEvent(AllowlistEvent.AddRequested)
        advanceUntilIdle()
        assertThat(vm.state.value.picking).isTrue()
        assertThat(vm.state.value.installed.map { it.packageName })
            .containsExactly("com.example.mybank", "com.whatsapp").inOrder()
    }

    @Test
    fun anOrdinaryApp_isAddedOnTheTap_andThePickerCloses() = runTest(dispatcher) {
        val vm = AllowlistViewModel(repository, phone)
        backgroundScope.launch { vm.state.collect {} }
        vm.onEvent(AllowlistEvent.AddRequested)
        vm.onEvent(AllowlistEvent.Picked(InstalledApp("com.example.mybank", "My Bank", false)))
        advanceUntilIdle()
        assertThat(added).containsExactly("com.example.mybank")
        assertThat(vm.state.value.picking).isFalse()
    }

    @Test
    fun aPersonalMessagesApp_waitsForTheWarning() = runTest(dispatcher) {
        val vm = AllowlistViewModel(repository, phone)
        backgroundScope.launch { vm.state.collect {} }

        vm.onEvent(AllowlistEvent.Picked(whatsApp))
        advanceUntilIdle()
        assertThat(added).isEmpty()
        assertThat(vm.state.value.confirming).isEqualTo(whatsApp)

        vm.onEvent(AllowlistEvent.WarningDismissed)
        advanceUntilIdle()
        assertThat(added).isEmpty()
        assertThat(vm.state.value.confirming).isNull()

        vm.onEvent(AllowlistEvent.Picked(whatsApp))
        vm.onEvent(AllowlistEvent.WarningConfirmed)
        advanceUntilIdle()
        assertThat(added).containsExactly("com.whatsapp")
        assertThat(vm.state.value.confirming).isNull()
    }
}
