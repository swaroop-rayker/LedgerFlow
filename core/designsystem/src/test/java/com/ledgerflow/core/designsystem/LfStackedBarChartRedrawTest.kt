package com.ledgerflow.core.designsystem

import android.graphics.RenderNode
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.chart.LfBarColumn
import com.ledgerflow.core.designsystem.chart.LfBarSegment
import com.ledgerflow.core.designsystem.chart.LfStackedBarChart
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The time chart does not redraw when it is handed the same numbers again
 * (`docs/PERF-120HZ-PLAN.md` §5).
 *
 * **Found in the tab-switch traces, not on screen.** Analytics re-reads its
 * snapshot on every resume. It flips `isLoading` and then lands an equal
 * snapshot, and each of those recompositions gave the chart a new list and a
 * new format lambda. Every draw then measured every label again. The
 * measuring took 6-13 ms of main thread twice per visit, for a picture that
 * had not changed: two late frames per Analytics switch at 120 Hz.
 *
 * The draws are counted on a layer around the chart. A child invalidation
 * re-records the layer it draws into, so the counter sees any redraw the chart
 * asks for. The second test is the control: new numbers must still redraw,
 * otherwise the first test would pass on a counter that never runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [REDRAW_ROBOLECTRIC_SDK])
class LfStackedBarChartRedrawTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var draws = 0
    private var generation by mutableIntStateOf(0)

    @Test
    fun recomposedWithEqualData_doesNotRedraw() {
        // A fresh, equal list and a fresh lambda on every generation: the
        // shape the resume re-read handed the chart.
        showChart { columns(scale = 1) }
        val before = draws

        generation++
        settle()

        assertThat(draws).isEqualTo(before)
    }

    @Test
    fun recomposedWithNewData_redraws() {
        showChart { gen -> columns(scale = gen + 1) }
        val before = draws

        generation++
        settle()

        assertThat(draws).isGreaterThan(before)
    }

    private fun showChart(columnsFor: (Int) -> List<LfBarColumn>) {
        rule.setContent {
            LfTheme {
                Box(
                    Modifier
                        .graphicsLayer()
                        .drawWithContent {
                            draws++
                            drawContent()
                        },
                ) {
                    val gen = generation
                    // New instances per generation, equal or not as the test chooses.
                    val columns = remember(gen) { columnsFor(gen) }
                    val format = remember(gen) { { minor: Long -> "₹$minor" } }
                    LfStackedBarChart(columns = columns, formatAxisValue = format)
                }
            }
        }
        settle()
        assertThat(draws).isGreaterThan(0)
    }

    /**
     * Composes, then draws the window the way the hardware renderer does.
     *
     * Robolectric draws no frames by itself. A `RenderNode`'s recording canvas
     * is a hardware canvas, so Compose re-records only the layers something
     * invalidated, as on a device. A software canvas would re-run every draw
     * and the first test could not pass.
     */
    private fun settle() {
        rule.waitForIdle()
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val node = RenderNode("probe").apply { setPosition(0, 0, root.width, root.height) }
            val canvas = node.beginRecording()
            root.draw(canvas)
            node.endRecording()
        }
        rule.waitForIdle()
    }

    private fun columns(scale: Int): List<LfBarColumn> = List(BUCKETS) { index ->
        LfBarColumn(
            id = index.toString(),
            label = "${index + 1} Oct",
            segments = listOf(
                LfBarSegment("g", "Groceries", (index + 1) * scale * 1_000L, Color(0xFF7FB3D5)),
            ),
        )
    }

    private companion object {
        // A month of daily buckets: more labels than the text measurer's cache.
        const val BUCKETS = 30
    }
}

private const val REDRAW_ROBOLECTRIC_SDK = 34
