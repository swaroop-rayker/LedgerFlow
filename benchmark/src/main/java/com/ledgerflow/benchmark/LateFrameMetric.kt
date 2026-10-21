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
 */
@OptIn(ExperimentalMetricApi::class)
internal class LateFrameMetric : TraceMetric() {

    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val row = traceSession.query(
            """
            SELECT
              COUNT(*) AS frames,
              SUM(CASE WHEN a.jank_type LIKE '%App Deadline Missed%' THEN 1 ELSE 0 END) AS late
            FROM actual_frame_timeline_slice a
            JOIN process p USING (upid)
            WHERE p.name = '${captureInfo.targetPackageName}'
            """.trimIndent(),
        ).first()
        return listOf(
            Measurement("framesTotal", row.long("frames").toDouble()),
            Measurement("framesLate", (row.nullableLong("late") ?: 0L).toDouble()),
        )
    }
}
