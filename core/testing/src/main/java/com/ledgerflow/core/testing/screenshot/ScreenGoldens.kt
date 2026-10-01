package com.ledgerflow.core.testing.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.TimeZone

/**
 * Where every module keeps its goldens, relative to the module (§12). The
 * Roborazzi plugin's record and verify tasks read and write here.
 */
public const val GOLDEN_DIR: String = "src/test/screenshots"

/** The Robolectric SDK every screen golden renders on. */
public const val GOLDEN_SDK: Int = 34

/**
 * A phone's width, and tall enough that a whole screen is in the capture — a
 * golden of the first half of a scrolling screen reviews the first half.
 */
public const val PHONE_1X: String = "w360dp-h1800dp"

/**
 * The same width, twice as tall, for font scale 2.0: Diagnostics' first 2x
 * recording at 2400 dp stopped half way down the screen.
 */
public const val PHONE_2X: String = "w360dp-h3600dp"

/**
 * Renders [content] at [fontScale] and compares it with `GOLDEN_DIR/[name].png`.
 *
 * Font scale goes through `Density(density, fontScale)`, which on API 34+
 * applies Android's own **non-linear** scaling, so a 2.0 golden is what a
 * phone at 2.0 draws — large type grows less than body type. The caller wraps
 * its own theme: this module does not depend on `:core:designsystem`.
 *
 * Every capture also runs the three structural checks below, so a golden
 * cannot be recorded over crowded or unlabelled controls, or clipped text.
 *
 * **The default time zone is pinned to UTC for the capture.** Robolectric pins
 * the locale but not the zone, so a screen that shows a time (the Ledger's
 * rows, a backup date) would render in IST where goldens are recorded and in
 * UTC on the Linux runner that verifies them — a golden that fails for the
 * hour of day, BUG33's kind of noise but not within any tolerance.
 */
public fun ComposeContentTestRule.captureScreenGolden(
    name: String,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val hostZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                content()
            }
        }
        assertTouchTargetsAreAtLeast48dp()
        assertEveryTappableNodeIsLabelled()
        assertNoUnwrappableTextIsClipped()
        onRoot().captureRoboImage("$GOLDEN_DIR/$name.png", roborazziOptions = LfScreenshotOptions)
    } finally {
        TimeZone.setDefault(hostZone)
    }
}

/** SPEC.md §9.6's minimum, in dp. */
public const val MIN_TOUCH_TARGET_DP: Int = 48

/**
 * §9.6: every tappable node gets a 48 × 48 dp target **of its own**.
 *
 * Compose already gives a small clickable a 48 dp touch area: hit testing
 * stretches it, and the stretched rectangle is what it reports as
 * `touchBoundsInRoot`. That holds even inside a clickable parent — checked
 * with a delete icon at the end of a clickable row, where a tap 2 dp outside
 * the 40 dp icon still deleted. A lone 40 dp Material `IconButton` is therefore
 * not a defect, and a first version of this check that said it was (every
 * Ledger row's delete) was wrong and was withdrawn before it reached SPEC.
 *
 * **What Compose cannot fix is two targets claiming the same space.** When
 * two small tappables sit closer than 48 dp, their stretched areas overlap and
 * a tap between them goes to whichever is nearer — a finger cannot reliably
 * choose. That is what this checks: any two tappable nodes, neither inside the
 * other, whose 48 dp areas (each node grown to at least 48 dp about its centre,
 * as Compose grows it) intersect, where at least one of them needed growing.
 * Full-size neighbours that merely touch pass. Positions are unclipped, so a
 * node below the viewport is judged by where it really is.
 */
public fun SemanticsNodeInteractionsProvider.assertTouchTargetsAreAtLeast48dp() {
    val nodes = tappableNodes()
    val failures = nodes.indices
        .flatMap { i -> (i + 1 until nodes.size).map { j -> nodes[i] to nodes[j] } }
        .filter { (a, b) -> !a.isAncestorOf(b) && !b.isAncestorOf(a) }
        // Two full-size neighbours that touch share no area Compose made up.
        .filter { (a, b) -> a.isBelowMinimum() || b.isBelowMinimum() }
        .filter { (a, b) -> a.targetArea().overlapsWithArea(b.targetArea()) }
        .map { (a, b) -> "${describe(a)}\n    shares its touch area with\n${describe(b)}" }
    check(failures.isEmpty()) {
        "Tappable nodes closer than $MIN_TOUCH_TARGET_DP dp, so a tap between them is ambiguous " +
            "(SPEC 9.6):\n" + failures.joinToString("\n")
    }
}

private fun SemanticsNode.minPx(): Float = with(layoutInfo.density) { MIN_TOUCH_TARGET_DP.dp.toPx() }

private fun SemanticsNode.isBelowMinimum(): Boolean =
    size.width + EPSILON_PX < minPx() || size.height + EPSILON_PX < minPx()

/** The node's unclipped bounds, grown to at least 48 dp about its centre. */
private fun SemanticsNode.targetArea(): Rect {
    val min = minPx()
    val w = maxOf(size.width.toFloat(), min)
    val h = maxOf(size.height.toFloat(), min)
    val cx = positionInRoot.x + size.width / 2f
    val cy = positionInRoot.y + size.height / 2f
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/** A real overlap, not two edges meeting or sub-pixel rounding. */
private fun Rect.overlapsWithArea(other: Rect): Boolean {
    val overlap = intersect(other)
    return overlap.width > EPSILON_PX && overlap.height > EPSILON_PX
}

private fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean =
    generateSequence(other.parent) { it.parent }.any { it.id == id }

/**
 * §9.6: every tappable node says what it is — its own text, or a content
 * description. An icon-only control without one is announced by TalkBack as
 * "button", which is a control nobody can use without sight.
 *
 * Read from the merged tree, which is what TalkBack reads: a button's label
 * normally comes from the `Text` inside it.
 */
public fun SemanticsNodeInteractionsProvider.assertEveryTappableNodeIsLabelled() {
    val unlabelled = tappableNodes().filter { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)
        text.all { it.isBlank() } && description.all { it.isBlank() } && editable == null
    }
    check(unlabelled.isEmpty()) {
        "Tappable nodes with no text or description (SPEC 9.6):\n" +
            unlabelled.joinToString("\n") { describe(it) }
    }
}

/**
 * BUG9's rule on every screen: **text that may not wrap must fit.**
 *
 * Control labels and money amounts render `softWrap = false` — a label never
 * breaks mid-word, an amount never splits across lines — which turns overflow
 * into silent clipping when the text is wider than its slot. P5 step 4's
 * goldens found it twice by eye: Analytics' totals cut to "₹88,600." and
 * onboarding's button cut to "I've written them do". This makes it a check.
 *
 * Measured as BUG35 measured it: the text's max intrinsic width against the
 * width it was given. Node bounds report the width given, and
 * `didOverflowWidth` reads true for most labels narrower than their slot, so
 * neither can see it. Text that *may* wrap is not checked — wrapping is how it
 * fits — and neither is an unbounded slot (a horizontally scrolling row).
 */
public fun SemanticsNodeInteractionsProvider.assertNoUnwrappableTextIsClipped() {
    val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
    val clipped = onAllNodes(hasTextLayout, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.let { node to it }
        }
        .filter { (_, layout) ->
            val input = layout.layoutInput
            !input.softWrap &&
                input.constraints.hasBoundedWidth &&
                layout.multiParagraph.intrinsics.maxIntrinsicWidth >
                input.constraints.maxWidth + EPSILON_PX
        }
    check(clipped.isEmpty()) {
        "Unwrappable text wider than its slot, so it is clipped (BUG9):\n" +
            clipped.joinToString("\n") { (node, layout) ->
                val density = node.layoutInfo.density
                val needs = with(density) { layout.multiParagraph.intrinsics.maxIntrinsicWidth.toDp().value.toInt() }
                val has = with(density) { layout.layoutInput.constraints.maxWidth.toDp().value.toInt() }
                "  \"${layout.layoutInput.text}\" needs $needs dp, has $has dp"
            }
    }
}

private fun SemanticsNodeInteractionsProvider.tappableNodes(): List<SemanticsNode> =
    onAllNodes(hasClickAction(), useUnmergedTree = false)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }

private fun describe(node: SemanticsNode): String {
    val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: "(unlabelled)"
    val role = node.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "no role"
    val density = node.layoutInfo.density
    val wDp = with(density) { node.size.width.toDp().value.toInt() }
    val hDp = with(density) { node.size.height.toDp().value.toInt() }
    return "  node #${node.id} \"$label\" [$role] $wDp x $hDp dp, visible at ${node.boundsInRoot}, " +
        "laid out at ${node.positionInRoot}"
}

/** Sub-pixel rounding from dp to px must not fail a target that is exactly 48 dp. */
private const val EPSILON_PX = 0.5f
