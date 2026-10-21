package com.ledgerflow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Inbox scrolling a queue of 120 candidates (`docs/PERF-120HZ-PLAN.md`
 * §1.5 and §3).
 *
 * The surface §3 flattened: each row was about twenty layout nodes, two
 * `TextButton`s and a swipe track, composed inside the frame that scrolls it in.
 * The queue is seeded through the capture write, not the ledger (see
 * `BenchmarkSeedActivity.fillInbox`), and discarded again afterwards, so the
 * other benchmarks and Home's "To review" card see the vault as they always did.
 *
 * Opened from Home's "To review" card, the way a user reaches it.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class InboxScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollBaselineProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), LateFrameMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        // As in LedgerScrollBenchmark: setup opens the app, so no startup mode.
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            ensureSeeded()
            device.setInbox(on = true)
            startActivityAndWait()
            // Home's "To review" card, as a user reaches the queue. Not the
            // `ledgerflow://inbox` link: before BUG59's fix it landed on Home,
            // and an A/B against a build older than the fix must still reach
            // the queue.
            device.tapText("Open Inbox")
            checkNotNull(awaitAppNode(INBOX_TIMEOUT_MS) { it.text?.toString() == "Inbox" }) {
                "The Inbox did not open"
            }
            // Unmeasured warm-up: the first fling of a fresh process compiles a
            // GPU pipeline (see LedgerScrollBenchmark).
            device.scrollingList().flingDownAndUp(device, times = 1)
        },
    ) {
        device.scrollingList().flingDownAndUp(device, FLINGS)
    }

    /** Leaves the vault as the other benchmarks and the store screenshots expect it. */
    @After
    fun emptyInbox() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).setInbox(on = false)
    }

    private companion object {
        const val ITERATIONS = 5
        const val FLINGS = 4
        const val INBOX_TIMEOUT_MS = 30_000L
    }
}
