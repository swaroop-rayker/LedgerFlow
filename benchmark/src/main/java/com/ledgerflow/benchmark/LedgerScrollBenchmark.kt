package com.ledgerflow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §11: no frame over 16.6 ms while the Ledger scrolls (P99), and a steady
 * state of at most 150 MB PSS.
 *
 * The list is five years of synthetic entries, paged (Paging 3, CLAUDE.md §8),
 * so the fling crosses page loads rather than scrolling a list already in
 * memory. Memory is read at the end of the scroll, which is the steady state
 * this screen settles into.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class LedgerScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollNoCompilation() = scroll(CompilationMode.None())

    @Test
    fun scrollBaselineProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scroll(compilation: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        compilationMode = compilation,
        // Not a startup measurement: the setup block opens the app and the
        // Ledger itself, and a startup mode would require the process dead
        // before the measured block, which is exactly what setup prevents.
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            ensureSeeded()
            startActivityAndWait()
            device.openTab("Ledger")
        },
    ) {
        device.scrollingList().flingDownAndUp(device, FLINGS)
    }

    private companion object {
        const val ITERATIONS = 5
        const val FLINGS = 4
    }
}
