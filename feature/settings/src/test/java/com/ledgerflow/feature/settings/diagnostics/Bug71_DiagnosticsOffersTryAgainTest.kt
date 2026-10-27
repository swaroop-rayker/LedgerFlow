package com.ledgerflow.feature.settings.diagnostics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.IngestDiagnosticsRepository
import com.ledgerflow.core.domain.usecase.GetIngestDiagnosticsUseCase
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
 * BUG71: when the data could not be opened to count, Diagnostics said "Close
 * the app and open it again" and offered nothing to press. It offers Try
 * again now, which reads the report afresh.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug71_DiagnosticsOffersTryAgainTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

    /** Unreadable until [opens] is set. */
    private class Diagnostics : IngestDiagnosticsRepository {
        var opens = false
        var reads = 0
        override suspend fun snapshot(sinceMillis: Long, nowMillis: Long): IngestDiagnostics? {
            reads++
            return if (opens) PreviewDiagnostics else null
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anUnreadableReport_offersTryAgain() {
        var retried = 0
        rule.setContent {
            LfTheme {
                DiagnosticsScreen(
                    state = DiagnosticsUiState(report = DiagnosticsReport.Unavailable),
                    onEvent = { if (it == DiagnosticsEvent.Retried) retried++ },
                    onOpenSuppressed = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Try again").performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test
    fun tryAgain_readsAfresh_andCanSucceed() {
        val diagnostics = Diagnostics()
        val vm = DiagnosticsViewModel(GetIngestDiagnosticsUseCase(diagnostics, Clock { NOW }))
        vm.onEvent(DiagnosticsEvent.Resumed)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Unavailable)

        diagnostics.opens = true
        vm.onEvent(DiagnosticsEvent.Retried)
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Loading)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(diagnostics.reads).isEqualTo(2)
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Ready(PreviewDiagnostics))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
