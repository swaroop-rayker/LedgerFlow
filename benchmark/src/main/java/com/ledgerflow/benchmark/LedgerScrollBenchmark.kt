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
            // Unmeasured warm-up. Each iteration is a fresh process, and the
            // first fling of a fresh process compiles a Vulkan pipeline the
            // driver has no cached copy of: measured 2026-09-29, one
            // `CreateGraphicsPipeline-CompileAfterCacheMiss` per iteration,
            // 15-67 ms, plus the late frames queued behind it. Five iterations
            // put five of those at the top of ~470 frames, so P99 measured the
            // GPU driver and nothing of the app. A real install keeps the cache
            // across launches (by hand, the second and later runs showed no
            // compile at all); the benchmark kills the process seconds after
            // each scroll, before the cache is saved. SPEC §11 states the
            // first-scroll compile separately rather than hiding it.
            device.scrollingList().flingDownAndUp(device, times = 1)
        },
    ) {
        device.scrollingList().flingDownAndUp(device, FLINGS)
    }

    private companion object {
        const val ITERATIONS = 5
        const val FLINGS = 4
    }
}
