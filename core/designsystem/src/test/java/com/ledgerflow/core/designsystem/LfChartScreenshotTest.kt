package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ledgerflow.core.designsystem.chart.LfBarColumn
import com.ledgerflow.core.designsystem.chart.LfBarDatum
import com.ledgerflow.core.designsystem.chart.LfBarSegment
import com.ledgerflow.core.designsystem.chart.LfBudgetBar
import com.ledgerflow.core.designsystem.chart.LfCalendarHeatmap
import com.ledgerflow.core.designsystem.chart.LfDonutChart
import com.ledgerflow.core.designsystem.chart.LfDonutSlice
import com.ledgerflow.core.designsystem.chart.LfHeatmapDay
import com.ledgerflow.core.designsystem.chart.LfHorizontalBarChart
import com.ledgerflow.core.designsystem.chart.LfStackedBarChart
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §12's screenshot gate, applied to A1–A5's charts (ADR-0005).
 *
 * **These are the surfaces a screenshot gate is actually for.** Everywhere else
 * in this codebase the sharper check is an assertion — `lineCount` for BUG9,
 * arithmetic for the axis. A chart has no such handle: whether a stacked bar is
 * readable, whether two donut arcs are distinguishable, whether an axis label
 * has collided with its neighbour, are all questions only a picture answers.
 *
 * **Every case renders twice, at font scale 1.0 and 2.0, in a narrow
 * container.** The narrow container is the point: at full phone width these
 * charts are comfortable, and the interesting behaviour is what happens when
 * they are not. The axis's label thinning is driven by *measured* text
 * (`LfAxisTicks.labelsThatFit`), so the 2.0 golden is the only place its
 * behaviour is visible.
 *
 * **Fixed colours, not the palette's derived ones.** `LfCategoryPalette` reads
 * the theme, so a golden using it would change whenever a theme token moved and
 * the diff would say "the chart changed" when the chart did not. Its own
 * conversion is covered by arithmetic elsewhere.
 *
 * **Review the diff, do not re-record it** (`CLAUDE.md` §12). A suite whose
 * failure mode is "run record again" asserts nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [CHART_ROBOLECTRIC_SDK])
class LfChartScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LfTheme {
                    Column(
                        modifier = Modifier
                            .width(NARROW_CHART.dp)
                            .padding(LfTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
                        content = { content() },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$CHART_GOLDEN_DIR/$name.png")
    }

    // ── A2 / A5: the donut ─────────────────────────────────────────────────

    @Test
    fun donut_1x() = capture("chart-donut-1x", 1.0f) { Donut() }

    @Test
    fun donut_2x() = capture("chart-donut-2x", 2.0f) { Donut() }

    /**
     * A near-even split with one hairline slice.
     *
     * The tiny slice is the interesting case: a sweep smaller than the gap
     * between arcs must not render as a negative arc or vanish into its
     * neighbour, which is what `coerceAtLeast(0f)` in the sweep is guarding.
     */
    @Composable
    private fun Donut() {
        LfDonutChart(
            slices = listOf(
                LfDonutSlice("a", "Groceries", 105_000L, Color(0xFF7FB3D5)),
                LfDonutSlice("b", "Home", 40_000L, Color(0xFFE59866)),
                LfDonutSlice("c", "Transport", 12_000L, Color(0xFF82C9A0)),
                LfDonutSlice("d", "Sundries", 200L, Color(0xFFC39BD3)),
            ),
        )
    }

    // ── A4: the merchant leaderboard ───────────────────────────────────────

    @Test
    fun leaderboard_1x() = capture("chart-leaderboard-1x", 1.0f) { Leaderboard() }

    @Test
    fun leaderboard_2x() = capture("chart-leaderboard-2x", 2.0f) { Leaderboard() }

    /**
     * Includes a merchant name far too long for the row, which is the BUG9
     * shape: the label may ellipsise, the amount beside it never may.
     */
    @Composable
    private fun Leaderboard() {
        LfHorizontalBarChart(
            data = listOf(
                LfBarDatum("1", "Zepto", 62_000L, "₹620.00", Color(0xFF7FB3D5)),
                LfBarDatum(
                    id = "2",
                    label = "Dr. Lal PathLabs Diagnostics Centre",
                    value = 41_000L,
                    formattedValue = "₹410.00",
                    color = Color(0xFF7FB3D5),
                ),
                LfBarDatum("3", "Swiggy", 18_000L, "₹180.00", Color(0xFF7FB3D5)),
                // A zero row must stay visible as an empty track, not collapse.
                LfBarDatum("4", "Blinkit", 0L, "₹0.00", Color(0xFF7FB3D5)),
            ),
        )
    }

    // ── A1: the stacked time chart ─────────────────────────────────────────

    @Test
    fun stackedBars_1x() = capture("chart-stacked-1x", 1.0f) { StackedBars() }

    @Test
    fun stackedBars_2x() = capture("chart-stacked-2x", 2.0f) { StackedBars() }

    // ── A6: the calendar heatmap ───────────────────────────────────────────

    @Test
    fun heatmap_1x() = capture("chart-heatmap-1x", 1.0f) { Heatmap() }

    @Test
    fun heatmap_2x() = capture("chart-heatmap-2x", 2.0f) { Heatmap() }

    /**
     * A month with one very heavy day.
     *
     * That is the case the alpha floor exists for: scaled purely by ratio, a
     * single ₹9,000 day would flatten every ₹200 day to near-invisible and the
     * grid would read as "one day of spending", which is the opposite of what a
     * heatmap is for. Two blank leading cells check the 1st lands under its
     * weekday, and several zero days check that an empty cell still renders.
     */
    @Composable
    private fun Heatmap() {
        LfCalendarHeatmap(
            days = buildList {
                repeat(2) { add(LfHeatmapDay(0, 0L, "", blank = true)) }
                val amounts = listOf(
                    45_000L, 0L, 12_000L, 900_000L, 20_000L, 0L, 31_000L,
                    18_000L, 0L, 62_000L, 0L, 24_000L, 51_000L, 8_000L,
                    0L, 39_000L, 15_000L, 0L, 72_000L, 11_000L, 0L,
                    28_000L, 44_000L, 0L, 19_000L, 33_000L, 0L, 57_000L,
                    22_000L, 0L,
                )
                amounts.forEachIndexed { index, amount ->
                    add(LfHeatmapDay(index + 1, amount, "₹${amount / 100}"))
                }
            },
        )
    }

    // ── A7: budget progress ────────────────────────────────────────────────

    @Test
    fun budgets_1x() = capture("chart-budgets-1x", 1.0f) { Budgets() }

    @Test
    fun budgets_2x() = capture("chart-budgets-2x", 2.0f) { Budgets() }

    /**
     * Three budgets in the three states that matter.
     *
     * Comfortable; **on course to overrun** — under budget today but with the
     * projection tick further along, which is the whole reason A7 draws two
     * marks; and already over, which switches to `warn`. A long category name
     * is included because the amount pair beside it must never truncate.
     */
    @Composable
    private fun Budgets() {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md)) {
            LfBudgetBar(
                label = "Groceries",
                formattedSpent = "₹4,200",
                formattedBudget = "₹12,000",
                fraction = 0.35f,
                projectedFraction = 0.55f,
                color = Color(0xFF7FB3D5),
            )
            LfBudgetBar(
                label = "Eating out and deliveries",
                formattedSpent = "₹3,100",
                formattedBudget = "₹4,000",
                fraction = 0.78f,
                projectedFraction = 1.0f,
                color = Color(0xFFE59866),
            )
            LfBudgetBar(
                label = "Transport",
                formattedSpent = "₹2,600",
                formattedBudget = "₹2,000",
                fraction = 1.3f,
                projectedFraction = 1.4f,
                color = Color(0xFF82C9A0),
            )
        }
    }

    /**
     * Twelve buckets in a narrow chart, which is more x-labels than can fit.
     *
     * That is deliberate: this is the only place the axis's label thinning is
     * visible, and the 2.0 golden is where it has to do real work. One empty
     * bucket is included because a week with no spending should read as
     * "nothing here" rather than as a gap in the series.
     */
    @Composable
    private fun StackedBars() {
        val amounts = listOf(
            45_000L, 62_000L, 0L, 88_000L, 31_000L, 74_000L,
            52_000L, 96_000L, 12_000L, 68_000L, 40_000L, 57_000L,
        )
        LfStackedBarChart(
            columns = amounts.mapIndexed { index, total ->
                LfBarColumn(
                    id = index.toString(),
                    label = "${index + 1} Aug",
                    segments = listOf(
                        LfBarSegment("g", "Groceries", total * 6 / 10, Color(0xFF7FB3D5)),
                        LfBarSegment("h", "Home", total * 3 / 10, Color(0xFFE59866)),
                        LfBarSegment("t", "Transport", total / 10, Color(0xFF82C9A0)),
                    ),
                )
            },
            formatAxisValue = { minor -> "₹${minor / 100}" },
        )
    }
}

/**
 * Matches `LfComponentScreenshotTest`'s SDK.
 *
 * Duplicated rather than shared because that one is `private` to its file, and
 * widening it so a second test can borrow it would make an incidental
 * implementation detail part of the module's surface. Two constants that must
 * agree is the smaller cost, and a mismatch shows up immediately as goldens
 * rendered against a different platform.
 */
private const val CHART_ROBOLECTRIC_SDK = 34

/** Narrower than a phone, so the charts are rendered under pressure. */
private const val NARROW_CHART = 300

private const val CHART_GOLDEN_DIR = "src/test/screenshots"
