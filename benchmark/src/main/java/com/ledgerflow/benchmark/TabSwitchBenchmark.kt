package com.ledgerflow.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tab switches, Home → Ledger → Analytics → Profile → Home (BUG57;
 * `docs/PERF-120HZ-PLAN.md` §1.5 and §5).
 *
 * The surface the transition bug lived on: Navigation's default 700 ms
 * cross-fade drew two full screens every frame, and the incoming one composed
 * inside it. Measured with [LateFrameMetric] beside [FrameTimingMetric], so the
 * before/after is a count of late frames rather than a percentile.
 *
 * Each switch waits out [SETTLE_MS] before the next tap, longer than the old
 * 700 ms transition, so a build with the old behaviour and one without it run
 * the same gesture and their numbers compare.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class TabSwitchBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun tabSwitchBaselineProfile() = switchTabs(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun switchTabs(compilation: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), LateFrameMetric()),
        compilationMode = compilation,
        // As in LedgerScrollBenchmark: setup opens the app, so no startup mode.
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            ensureSeeded()
            startActivityAndWait()
            // Unmeasured warm-up round: the first visit to each tab in a fresh
            // process composes it for the first time and compiles its GPU
            // pipelines (see LedgerScrollBenchmark). A real user pays that once
            // per launch, not once per switch.
            TABS.forEach { device.openTab(it); SystemClock.sleep(SETTLE_MS) }
        },
    ) {
        repeat(ROUNDS) {
            TABS.forEach { device.openTab(it); SystemClock.sleep(SETTLE_MS) }
        }
    }

    private companion object {
        // The fourth tab was "More" until V2-2 and "Settings" until design-system
        // D4. Profile is a much lighter screen than the Settings tab was, so runs
        // from before D4 do not compare on that leg.
        val TABS = listOf("Ledger", "Analytics", "Profile", "Home")
        const val ITERATIONS = 5
        const val ROUNDS = 2
        const val SETTLE_MS = 1_000L
    }
}
