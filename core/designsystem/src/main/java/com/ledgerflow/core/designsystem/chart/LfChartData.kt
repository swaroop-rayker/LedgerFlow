package com.ledgerflow.core.designsystem.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The data types every `Lf*` chart takes.
 *
 * Gathered in one file so each chart composable's own file holds only the
 * drawing, and so the shape a caller has to build is in one place rather than
 * scattered across four.
 *
 * **Money is `Long` minor units in every one of these** (Law 3). A chart is
 * handed the amount and derives its own angles, heights and fractions; nothing
 * upstream pre-divides into a `Double` and hands down a ratio, because that is
 * how a money value quietly becomes a floating-point one.
 *
 * **Every type carries a `label`,** and it is not optional. It is what a screen
 * reader says (§9.6), and a slice or bar nobody can name is decoration rather
 * than data.
 *
 * All are `@Immutable`: these live in `LazyColumn` items and `UiState`
 * classes, and an unstable parameter in a hot composable is a §8 regression.
 */
@Immutable
public data class LfDonutSlice(
    val id: String,
    val label: String,
    val value: Long,
    val color: Color,
)

/** One row of a ranked bar list — merchant leaderboard, capture coverage. */
@Immutable
public data class LfBarDatum(
    val id: String,
    val label: String,
    val value: Long,
    /** Pre-rendered by the caller, which owns the currency (§5.8). */
    val formattedValue: String,
    val color: Color,
)

/** One category's share of a single time bucket. */
@Immutable
public data class LfBarSegment(
    val id: String,
    val label: String,
    val value: Long,
    val color: Color,
)

/**
 * One bucket of a time series — a day, a week or a month.
 *
 * Already binned to the display resolution when it gets here (§11): the chart
 * never receives more columns than it has horizontal pixels, and a change of
 * window is a re-query rather than a transform.
 */
@Immutable
public data class LfBarColumn(
    val id: String,
    val label: String,
    val segments: List<LfBarSegment>,
) {
    public val total: Long get() = segments.sumOf { it.value }
}

/**
 * One cell of A6's calendar heatmap.
 *
 * [dayOfMonth] is 1-based and [amount] is money in minor units (Law 3).
 * [blank] marks the leading cells before the 1st so the grid lines up under the
 * right weekday column — a padding cell, not a day with nothing on it, which is
 * a different thing the grid also has to show.
 */
@Immutable
public data class LfHeatmapDay(
    val dayOfMonth: Int,
    val amount: Long,
    val formattedAmount: String,
    val blank: Boolean = false,
)

/**
 * One tile of A3's optional treemap.
 *
 * `value` is money in minor units (Law 3); [LfTreemapLayout] turns it into an
 * area, which is a chart coordinate and legitimately real-valued.
 */
@Immutable
public data class LfTreemapDatum(
    val id: String,
    val label: String,
    val value: Long,
    val color: Color,
)

/**
 * One period, carrying **both books as separate figures**.
 *
 * Two fields rather than one signed number, deliberately: a signed total is a
 * net, and a type that can hold one is a type someone will eventually put one
 * into. Neither field is ever negative.
 */
@Immutable
public data class LfMirroredColumn(
    val id: String,
    val label: String,
    val creditMinor: Long,
    val debitMinor: Long,
)
