package com.ledgerflow.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * How the app moves between screens (SPEC.md §9.3, §9.5; BUG57).
 *
 * **Why this exists.** With no transitions set, Navigation Compose falls back
 * to `fadeIn(tween(700))` and `fadeOut(tween(700))` running *at the same
 * time*: for most of a second both screens were drawn at partial opacity, one
 * on top of the other, and at the midpoint each was half-transparent. That is
 * the "fuzzy, overlapping text" the owner reported on every tab switch.
 *
 * Both kinds of move here are therefore **fade-throughs**: the outgoing screen
 * is gone ([fadeOutMillis]) before the incoming one starts to appear. No frame
 * shows two screens' text, and `Bug57_TabSwitchDoesNotOverlapScreensTest`
 * checks that frame by frame.
 *
 * - **Tab to tab** is a plain fade-through, ~180 ms. No scale: a scaled layer
 *   re-rasterises its text every frame and reads as soft, which is the
 *   complaint this replaces.
 * - **Anything else** (the entry form, review, the settings pages, the Inbox)
 *   is a short shared-axis slide with the same fade-through inside it; back
 *   plays it in reverse, and RTL mirrors it. Predictive back seeks through
 *   these same transitions.
 *
 * The kind is decided from the two screens, not from Navigation's push/pop
 * flag: `switchTab` pops to the start destination and then navigates, so which
 * of the four lambdas runs for a tab switch says nothing reliable about it.
 */
@Immutable
internal class NavMotion(
    private val enabled: Boolean,
    private val fadeOutMillis: Int,
    private val tabFadeInMillis: Int,
    private val axisMillis: Int,
    private val axisSlidePx: Int,
    private val rtl: Boolean,
) {

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, forward: Boolean): EnterTransition {
        // Not None either -- see [exit]. A fade's initial value is drawn on the
        // frame the screen is added, before any animation ticks, so the
        // incoming screen starts at alpha 0 and the switch is one clean step.
        if (!enabled) return fadeIn(snap())
        return if (scope.isTabSwitch()) {
            fadeIn(tween(tabFadeInMillis, delayMillis = fadeOutMillis, easing = LinearOutSlowInEasing))
        } else {
            val from = direction(forward) * axisSlidePx
            slideInHorizontally(tween(axisMillis, easing = FastOutSlowInEasing)) { from } +
                fadeIn(
                    tween(
                        axisMillis - fadeOutMillis,
                        delayMillis = fadeOutMillis,
                        easing = LinearOutSlowInEasing,
                    ),
                )
        }
    }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, forward: Boolean): ExitTransition {
        // Not ExitTransition.None: measured under BUG57's test, None/None kept
        // both screens composed *at full opacity* for a frame while the
        // transition settled -- both screens' text at full strength, which is
        // BUG57 again for exactly the users who turned animations off. A snap
        // takes the outgoing screen to alpha 0 on the first tick.
        if (!enabled) return fadeOut(snap())
        val fade = fadeOut(tween(fadeOutMillis, easing = FastOutLinearInEasing))
        return if (scope.isTabSwitch()) {
            fade
        } else {
            val to = -direction(forward) * axisSlidePx
            slideOutHorizontally(tween(axisMillis, easing = FastOutSlowInEasing)) { to } + fade
        }
    }

    /** +1 when the incoming screen arrives from the right. */
    private fun direction(forward: Boolean): Int = if (forward != rtl) 1 else -1

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
        initialState.destination.isTab() && targetState.destination.isTab()

    private fun NavDestination.isTab(): Boolean = BottomBarDestinations.any { hasRoute(it::class) }
}

/**
 * The app's [NavMotion], from [LfTheme.motion] and the system's animation
 * setting.
 *
 * [animationsEnabled] defaults to the system's "Remove animations" switch
 * (§9.5: every animation respects `ANIMATOR_DURATION_SCALE`). Compose already
 * scales its own animations by it; this turns the transitions off outright,
 * which is what makes the setting testable, and is the one seam v2's own
 * Animations toggle (V2-3) plugs into.
 */
@Composable
internal fun rememberNavMotion(animationsEnabled: Boolean = rememberSystemAnimationsEnabled()): NavMotion {
    val tokens = LfTheme.motion
    val slidePx = with(LocalDensity.current) { tokens.axisSlide.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(animationsEnabled, tokens, slidePx, rtl) {
        NavMotion(
            enabled = animationsEnabled,
            fadeOutMillis = tokens.fadeOut,
            tabFadeInMillis = tokens.micro,
            axisMillis = tokens.standard,
            axisSlidePx = slidePx,
            rtl = rtl,
        )
    }
}

/**
 * False when the system's animator duration scale is 0 ("Remove animations").
 *
 * Re-read on every resume: the switch lives in another app, so coming back is
 * the only moment it can have changed.
 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(context.systemAnimationsEnabled()) }
    LifecycleResumeEffect(context) {
        enabled = context.systemAnimationsEnabled()
        onPauseOrDispose { }
    }
    return enabled
}

private fun Context.systemAnimationsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/**
 * The app's `NavHost`, with [motion] on all four transitions.
 *
 * Separate from the graph so the regression test drives exactly this wiring
 * with stand-in screens: a test that rebuilt the four lambdas itself would
 * pass on the day someone dropped them from here.
 */
@Composable
internal fun LfNavHost(
    navController: NavHostController,
    motion: NavMotion,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Dashboard,
        modifier = modifier,
        enterTransition = { motion.enter(this, forward = true) },
        exitTransition = { motion.exit(this, forward = true) },
        popEnterTransition = { motion.enter(this, forward = false) },
        popExitTransition = { motion.exit(this, forward = false) },
        builder = builder,
    )
}
