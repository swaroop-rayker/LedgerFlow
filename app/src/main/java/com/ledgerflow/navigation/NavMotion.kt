package com.ledgerflow.navigation

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.MotionLevel

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
 * - **Under Animations > Reduced** (V2-3) that slide is dropped and the
 *   fade-through stays; **Off** snaps (see [enter]/[exit]).
 *
 * The kind is decided from the two screens, not from Navigation's push/pop
 * flag: `switchTab` pops to the start destination and then navigates, so which
 * of the four lambdas runs for a tab switch says nothing reliable about it.
 */
@Immutable
internal class NavMotion(
    private val level: MotionLevel,
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
        if (level == MotionLevel.OFF) return fadeIn(snap())
        return if (scope.isTabSwitch()) {
            fadeIn(tween(tabFadeInMillis, delayMillis = fadeOutMillis, easing = LinearOutSlowInEasing))
        } else if (level == MotionLevel.REDUCED) {
            // Animations > Reduced (V2-3): the same fade-through, without the
            // slide. Nothing moves sideways, and BUG57's rule still holds.
            fadeIn(tween(axisMillis - fadeOutMillis, delayMillis = fadeOutMillis, easing = LinearOutSlowInEasing))
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
        if (level == MotionLevel.OFF) return fadeOut(snap())
        val fade = fadeOut(tween(fadeOutMillis, easing = FastOutLinearInEasing))
        return if (scope.isTabSwitch() || level == MotionLevel.REDUCED) {
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
 * The app's [NavMotion], from [LfTheme.motion] and the resolved [level].
 *
 * [level] is Settings > Appearance > Animations with the phone's "Remove
 * animations" switch already applied (§9.5: the phone's setting always wins;
 * `AnimationPreference.motionLevel`). `MainActivity` resolves it, re-reading the
 * phone's setting on every resume, and scales every other Compose animation by
 * it too (`AppMotionDurationScale`). Off is set here explicitly as well as
 * through that scale, because the explicit snap is what makes it testable.
 */
@Composable
internal fun rememberNavMotion(level: MotionLevel): NavMotion {
    val tokens = LfTheme.motion
    val slidePx = with(LocalDensity.current) { tokens.axisSlide.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(level, tokens, slidePx, rtl) {
        NavMotion(
            level = level,
            fadeOutMillis = tokens.fadeOut,
            tabFadeInMillis = tokens.micro,
            axisMillis = tokens.standard,
            axisSlidePx = slidePx,
            rtl = rtl,
        )
    }
}

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
    startDestination: Destination = Destination.Dashboard,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { motion.enter(this, forward = true) },
        exitTransition = { motion.exit(this, forward = true) },
        popEnterTransition = { motion.enter(this, forward = false) },
        popExitTransition = { motion.exit(this, forward = false) },
        builder = builder,
    )
}
