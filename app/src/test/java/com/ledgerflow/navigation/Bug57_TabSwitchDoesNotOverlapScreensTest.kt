package com.ledgerflow.navigation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.MotionLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

/**
 * **BUG57: switching tabs drew two screens' text over each other.**
 *
 * `LedgerFlowNavHost` set no transitions, so Navigation Compose used its
 * default: `fadeIn(tween(700))` and `fadeOut(tween(700))` *at the same time*.
 * For most of a second every tab switch showed both screens at partial opacity,
 * half-transparent each at the midpoint -- the "fuzzy, overlapping text" the
 * owner reported (V2-PLAN BUG-A). [NavMotion] makes every move a fade-through.
 *
 * Drives the production wiring -- [LfNavHost], [NavMotion], [switchTab] and
 * the real [Destination] routes -- with stand-in screens whose text is one
 * pure colour each: Home red, everything else blue. With the clock paused it
 * captures **every frame** of a transition and fails on any frame that holds
 * ink of both colours, however faint. On the 700 ms default the midpoint holds
 * both at about half strength.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it nothing is drawn and every frame is blank,
// which would pass the overlap check trivially.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The plain Application: LedgerFlowApplication starts Hilt, WorkManager and
// StrictMode, none of which a transition needs.
@Config(sdk = [34], qualifiers = "w360dp-h640dp", application = Application::class)
class Bug57_TabSwitchDoesNotOverlapScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun tabSwitch_neverShowsTwoScreensAtOnce_andFinishesQuickly() {
        show()
        assertFramesNeverOverlap("Home -> Ledger tab", settledWithinMs = TAB_SETTLE_MS) {
            navController.switchTab(Destination.Ledger)
        }
        assertFramesNeverOverlap("Ledger -> Home tab", settledWithinMs = TAB_SETTLE_MS, toHome = true) {
            navController.switchTab(Destination.Dashboard)
        }
    }

    @Test
    fun forwardAndBack_neverShowTwoScreensAtOnce() {
        show()
        assertFramesNeverOverlap("Home -> entry form", settledWithinMs = AXIS_SETTLE_MS) {
            navController.navigate(Destination.Entry())
        }
        assertFramesNeverOverlap("entry form -> back to Home", settledWithinMs = AXIS_SETTLE_MS, toHome = true) {
            navController.popBackStack()
        }
    }

    @Test
    fun animationsOff_switchesWithoutMotionOrOverlap() {
        show(MotionLevel.OFF)
        assertFramesNeverOverlap("Home -> Ledger, animations off", settledWithinMs = OFF_SETTLE_MS) {
            navController.switchTab(Destination.Ledger)
        }
    }

    /**
     * Settings > Appearance > Animations > Reduced (V2-3): forward and back are
     * fade-throughs with no slide. The overlap rule still holds, and the
     * incoming screen's text never moves sideways while it fades in.
     */
    @Test
    fun reduced_fadesThroughWithoutSliding() {
        show(MotionLevel.REDUCED)
        val edges = mutableListOf<Int>()
        assertFramesNeverOverlap("Home -> entry form, reduced", settledWithinMs = AXIS_SETTLE_MS, onFrame = {
            inkLeftEdge()?.let(edges::add)
        }) {
            navController.navigate(Destination.Entry())
        }
        assertWithMessage("the entry form's left edge, frame by frame, while it fades in").that(edges).isNotEmpty()
        assertWithMessage("the entry form's left edge moved: ${edges.distinct()}")
            .that(edges.max() - edges.min())
            .isAtMost(1)
        assertFramesNeverOverlap(
            "entry form -> back to Home, reduced",
            settledWithinMs = AXIS_SETTLE_MS,
            toHome = true,
        ) {
            navController.popBackStack()
        }
    }

    /**
     * The measurement above can see a slide: under Full the same move shifts
     * the incoming text sideways. Without this, a left edge that never moved
     * could mean only that the measure was blind.
     */
    @Test
    fun full_doesSlide_soTheReducedCheckCanSeeOne() {
        show()
        val edges = mutableListOf<Int>()
        assertFramesNeverOverlap("Home -> entry form, full", settledWithinMs = AXIS_SETTLE_MS, onFrame = {
            inkLeftEdge()?.let(edges::add)
        }) {
            navController.navigate(Destination.Entry())
        }
        assertWithMessage("the entry form's left edge, frame by frame").that(edges.max() - edges.min())
            .isAtLeast(SLIDE_VISIBLE_PX)
    }

    @Test
    fun tabKeepsItsScroll() {
        show()
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { navController.switchTab(Destination.Ledger) }
        rule.onNodeWithTag(LIST_TAG).performScrollToIndex(SCROLLED_TO)
        rule.runOnUiThread { navController.switchTab(Destination.Dashboard) }
        rule.waitForIdle()
        rule.runOnUiThread { navController.switchTab(Destination.Ledger) }
        rule.waitForIdle()
        rule.onNodeWithText(row(SCROLLED_TO)).assertIsDisplayed()
    }

    private fun show(level: MotionLevel = MotionLevel.FULL) {
        rule.setContent {
            LfTheme {
                navController = rememberNavController()
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    LfNavHost(
                        navController = navController,
                        motion = rememberNavMotion(level),
                    ) {
                        composable<Destination.Dashboard> { Lines("Home", HOME_INK) }
                        composable<Destination.Ledger> { LedgerList() }
                        composable<Destination.Analytics> { Lines("Analytics", OTHER_INK) }
                        composable<Destination.Profile> { Lines("Profile", OTHER_INK) }
                        composable<Destination.Entry> { Lines("Entry form", OTHER_INK) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * Runs [navigate] with the clock paused and inspects every frame until
     * [settledWithinMs] plus a margin. Fails on a frame with both inks, and if
     * the incoming screen is not fully drawn, alone, by [settledWithinMs].
     */
    private fun assertFramesNeverOverlap(
        what: String,
        settledWithinMs: Long,
        toHome: Boolean = false,
        onFrame: () -> Unit = {},
        navigate: () -> Unit,
    ) {
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread(navigate)
        val start = rule.mainClock.currentTime
        var settledAt: Long? = null
        while (rule.mainClock.currentTime - start <= settledWithinMs + MARGIN_MS) {
            rule.mainClock.advanceTimeByFrame()
            val elapsed = rule.mainClock.currentTime - start
            val (home, other) = ink()
            assertWithMessage("$what at $elapsed ms: Home's ink $home and the other screen's $other in one frame")
                .that(min(home, other))
                .isLessThan(FAINT)
            val (incoming, outgoing) = if (toHome) home to other else other to home
            if (settledAt == null && incoming > SOLID && outgoing < FAINT) settledAt = elapsed
            onFrame()
        }
        assertWithMessage("$what: when the incoming screen was fully drawn, alone (ms)")
            .that(settledAt)
            .isAtMost(settledWithinMs)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    /** The strongest red and blue ink in the frame, 0 (none) to 1 (pure). */
    private fun ink(): Pair<Float, Float> {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var red = 0f
        var blue = 0f
        for (y in 0 until pixels.height step 2) {
            for (x in 0 until pixels.width step 2) {
                val c = pixels[x, y]
                red = max(red, min(c.red - c.green, c.red - c.blue))
                blue = max(blue, min(c.blue - c.red, c.blue - c.green))
            }
        }
        return red to blue
    }

    /**
     * The leftmost column holding the other screen's (blue) ink at a visible
     * strength, or null when there is none. Every row is scanned: a fading
     * glyph's edge is still in the same column at any opacity.
     */
    private fun inkLeftEdge(): Int? {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val c = pixels[x, y]
                if (min(c.blue - c.red, c.blue - c.green) > EDGE_INK) return x
            }
        }
        return null
    }

    @Composable
    private fun Lines(name: String, ink: Color) {
        Column(Modifier.fillMaxSize()) {
            repeat(LINES) { Text("$name screen, line $it", color = ink, fontSize = TEXT_SP.sp) }
        }
    }

    @Composable
    private fun LedgerList() {
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(LIST_TAG), state = rememberLazyListState()) {
            items(count = ROWS, key = { it }, contentType = { LIST_TAG }) {
                Text(row(it), color = OTHER_INK, fontSize = TEXT_SP.sp)
            }
        }
    }

    private fun row(index: Int) = "Ledger row $index"

    private companion object {
        val HOME_INK = Color(0xFFFF0000)
        val OTHER_INK = Color(0xFF0000FF)

        /** Ink below this is invisible: alpha ~0.03 over white. */
        const val FAINT = 0.03f

        /** A screen drawn at (near) full opacity. */
        const val SOLID = 0.9f

        /** Ink strong enough to place an edge by: a quarter-opaque glyph. */
        const val EDGE_INK = 0.25f

        /** Full's 30 dp slide, seen as at least this many pixels of travel. */
        const val SLIDE_VISIBLE_PX = 10

        // NavMotion's budgets: 60 ms out + 120 ms in for a tab, 240 ms for the
        // slide, plus a frame of slack each for the frame the navigation lands.
        const val TAB_SETTLE_MS = 200L
        const val AXIS_SETTLE_MS = 260L
        // Off: three 16 ms frames -- the navigation lands, the old screen
        // snaps out, the new one in. Measured 48 ms. With None/None instead
        // of snaps, the second frame held both screens at full strength.
        const val OFF_SETTLE_MS = 48L
        const val MARGIN_MS = 100L

        const val LINES = 12
        const val TEXT_SP = 22
        const val ROWS = 100
        const val SCROLLED_TO = 40
        const val LIST_TAG = "ledger-list"
    }
}
