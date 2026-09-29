package com.ledgerflow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §11: cold start ≤ 700 ms to first frame (P50), warm start ≤ 250 ms.
 *
 * Each start is measured twice: with no ahead-of-time compilation, and with
 * the shipped baseline profile required. The pair is what says whether the
 * profile earns its place; `Require` fails loudly if the APK has none, rather
 * than quietly measuring the same thing twice.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartNoCompilation() = start(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldStartBaselineProfile() =
        start(StartupMode.COLD, CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun warmStartNoCompilation() = start(StartupMode.WARM, CompilationMode.None())

    @Test
    fun warmStartBaselineProfile() =
        start(StartupMode.WARM, CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: StartupMode, compilation: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = mode,
        iterations = ITERATIONS,
        setupBlock = { ensureSeeded() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        // Enough for a stable median on one device; more is heat on a laptop
        // that already overheats (memory: dev-box-ram-and-heat-budget).
        const val ITERATIONS = 10
    }
}
