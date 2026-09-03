package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.chart.LfBarColumn
import com.ledgerflow.core.designsystem.chart.LfBarSegment
import com.ledgerflow.core.designsystem.chart.LfStackedBarChart
import com.ledgerflow.core.designsystem.chart.LfViewportGesture
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BUG19 — the time chart ate the page's vertical scroll.
 *
 * **Found on device, not in a test.** A1's pan/zoom was implemented with
 * `detectTransformGestures`, which consumes *any* pan once it passes touch
 * slop — a one-finger vertical drag included. The chart fills roughly a third
 * of the Analytics screen, so dragging on the most obvious thing on it did
 * nothing at all: the page would not scroll, and there was no error, no visual
 * feedback and nothing in the log. It looked like a frozen screen.
 *
 * The fix is a hand-rolled detector that consumes nothing until a **second**
 * pointer is down, so a single-finger drag falls through to the `LazyColumn`.
 *
 * This test is the shape of the bug rather than of the fix: it asserts the
 * *page still scrolls*, which stays true however the gesture handling is
 * rewritten later.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SCROLL_ROBOLECTRIC_SDK])
class Bug19_ChartDoesNotSwallowVerticalScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aVerticalDragOnTheChartStillScrollsThePage() {
        var listState: LazyListState? = null

        composeRule.setContent {
            LfTheme {
                val state = rememberLazyListState()
                listState = state
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    item {
                        LfStackedBarChart(
                            columns = columns(),
                            formatAxisValue = { "$it" },
                            modifier = Modifier.testTag(CHART_TAG),
                            // The callback being present is the point: with it
                            // null the gesture modifiers are never attached and
                            // the test could not fail.
                            onViewportChange = {},
                        )
                    }
                    items(List(FILLER_ROWS) { it }) { index ->
                        Text("row $index", modifier = Modifier.height(ROW_HEIGHT.dp))
                    }
                }
            }
        }

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val scrolled = requireNotNull(listState) { "list state was never captured" }
        assertThat(
            scrolled.firstVisibleItemIndex > 0 || scrolled.firstVisibleItemScrollOffset > 0,
        ).isTrue()
    }

    /**
     * A pinch still reaches the chart.
     *
     * The fix would be worthless if it had disabled zoom to let scrolling
     * through, so the other half is asserted too — a chart that scrolls and
     * cannot zoom is the same feature missing, one layer down.
     */
    @Test
    fun aPinchIsStillReportedAsAZoom() {
        val reported = mutableListOf<LfViewportGesture>()

        composeRule.setContent {
            LfTheme {
                LfStackedBarChart(
                    columns = remember { columns() },
                    formatAxisValue = { "$it" },
                    modifier = Modifier.testTag(CHART_TAG),
                    onViewportChange = { reported += it },
                )
            }
        }

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput {
            val centreY = height / 2f
            down(FIRST_POINTER, Offset(width * 0.4f, centreY))
            down(SECOND_POINTER, Offset(width * 0.6f, centreY))
            moveTo(FIRST_POINTER, Offset(width * 0.1f, centreY))
            moveTo(SECOND_POINTER, Offset(width * 0.9f, centreY))
            up(FIRST_POINTER)
            up(SECOND_POINTER)
        }
        composeRule.waitForIdle()

        assertThat(reported.filterIsInstance<LfViewportGesture.Zoom>()).isNotEmpty()
    }

    private fun columns(): List<LfBarColumn> = List(BUCKETS) { index ->
        LfBarColumn(
            id = index.toString(),
            label = "$index Aug",
            segments = listOf(
                LfBarSegment("g", "Groceries", (index + 1) * 1_000L, Color(0xFF7FB3D5)),
            ),
        )
    }

    private companion object {
        const val CHART_TAG = "chart"
        const val FILLER_ROWS = 40
        const val ROW_HEIGHT = 48
        const val BUCKETS = 10
        const val FIRST_POINTER = 0
        const val SECOND_POINTER = 1
    }
}

/** Matches the other Robolectric suites in this module. */
private const val SCROLL_ROBOLECTRIC_SDK = 34
