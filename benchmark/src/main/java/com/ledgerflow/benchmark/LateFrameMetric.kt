package com.ledgerflow.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Late frames per iteration: the app's frames the system marked
 * `App Deadline Missed` (`docs/PERF-120HZ-PLAN.md` §1.3).
 *
 * The number P99 hides. One first-use pipeline compile puts a single huge
 * frame at the top of a run and moves the percentile; this counts how many
 * frames the user actually saw arrive late, which is what a transition that
 * draws two screens per frame shows up in. Reported beside the frame total, so
 * a run with fewer frames is not read as an improvement.
 *
 * **`framesOverBudget` is the count `framesLate` misses.** Read from the tab
 * switch traces (2026-10-19): the incoming tab's first frame, 20-37 ms of main
 * thread, was usually classed `Dropped Frame` rather than late, because the
 * ripple's RenderThread-only frames kept the display fed while it ran. Counting
 * `Dropped Frame` instead would be noise -- most of those are 3-5 ms frames
 * superseded by a ripple frame -- so this counts the work itself: main-thread
 * `Choreographer#doFrame` slices longer than their own frame's budget.
 */
@OptIn(ExperimentalMetricApi::class)
internal class LateFrameMetric : TraceMetric() {

    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val app = captureInfo.targetPackageName
        val row = traceSession.query(
            """
            SELECT
              COUNT(*) AS frames,
              SUM(CASE WHEN a.jank_type LIKE '%App Deadline Missed%' THEN 1 ELSE 0 END) AS late
            FROM actual_frame_timeline_slice a
            JOIN process p USING (upid)
            WHERE p.name = '$app'
            """.trimIndent(),
        ).first()
        // A doFrame's name ends in its vsync id, which is the expected
        // timeline row's name; that row's duration is the budget (8.3 ms at
        // 120 Hz, 16.7 ms when the panel dropped to 60).
        val overBudget = traceSession.query(
            """
            SELECT COUNT(*) AS over
            FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id
            JOIN thread t USING (utid)
            JOIN process p USING (upid)
            WHERE p.name = '$app' AND t.tid = p.pid AND s.depth = 0
              AND s.name LIKE 'Choreographer#doFrame %'
              AND s.dur > (
                SELECT MIN(e.dur) FROM expected_frame_timeline_slice e
                WHERE e.upid = p.upid AND e.name = substr(s.name, 23)
              )
            """.trimIndent(),
        ).first()
        return listOf(
            Measurement("framesTotal", row.long("frames").toDouble()),
            Measurement("framesLate", (row.nullableLong("late") ?: 0L).toDouble()),
            Measurement("framesOverBudget", overBudget.long("over").toDouble()),
        )
    }
}
