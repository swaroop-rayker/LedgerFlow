package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §12's screenshot gate, and the first thing in this repository that actually
 * renders a pixel in CI (`CLAUDE.md` §11 open item 5 — Roborazzi was listed in
 * §4 and required by §12 and had never been wired up).
 *
 * ## Why these shapes and not a gallery of every component
 *
 * A screenshot suite earns its keep by catching the failures that *only* show
 * up as pixels, and this codebase already knows which those are:
 *
 * - **BUG9** — a control's label breaking mid-word when a row of actions
 *   overflows. `Bug9_ControlLabelsNeverWrapTest` asserts `lineCount` on the
 *   real layout, which is the sharper check; what it cannot show is what the
 *   overflow *looks* like, which is what a reviewer needs to judge whether the
 *   degradation is acceptable.
 * - **BUG5** — layout at font scale 2.0. §9.6 requires it and previews were
 *   never being diffed, which is exactly how BUG9 shipped.
 *
 * So every case here is rendered **twice**, at scale 1.0 and 2.0, in a
 * deliberately narrow container. The narrow container is the point: at phone
 * width these components are comfortable, and the interesting behaviour is what
 * happens when they are not.
 *
 * ## Reviewing a diff, not re-recording it
 *
 * `CLAUDE.md` §12 requires screenshot diffs to be **reviewed, not blindly
 * re-recorded**, and that rule is the whole value of the gate — a suite whose
 * failure mode is "run record again" is a suite that asserts nothing. When a
 * diff appears, look at it and decide whether the new rendering is *better*.
 * Re-record only after that decision, and say in the commit what changed and
 * why it is an improvement.
 *
 * ## Robolectric, not a device
 *
 * `GraphicsMode.NATIVE` is what makes Robolectric rasterise real pixels rather
 * than returning a blank bitmap; without it every golden is an empty image and
 * every comparison passes. That is the failure mode worth knowing about here,
 * because it is silent and it looks like success.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK])
class LfComponentScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Renders [content] at [fontScale] and writes one golden.
     *
     * Font scale is provided through [LocalDensity] rather than a Robolectric
     * qualifier so the two scales differ by exactly one value and nothing else —
     * a qualifier change would also move the screen size, and then a diff would
     * not tell you which of the two caused it.
     */
    private fun capture(name: String, fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LfTheme {
                    Column(
                        modifier = Modifier
                            .width(NARROW_CARD.dp)
                            .padding(LfTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
                        content = { content() },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$GOLDEN_DIR/$name.png")
    }

    /** BUG9's exact failing shape: three actions in a card-width row. */
    @Composable
    private fun ThreeActionCard() {
        LfCard {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                Text(
                    text = "Groceries",
                    style = LfTheme.typography.bodyL,
                    color = LfTheme.colors.textPrimary,
                )
                LfActionRow(alignment = LfActionAlignment.End) {
                    LfButton("Rename", {}, style = LfButtonStyle.Inline)
                    LfButton("Add sub", {}, style = LfButtonStyle.Inline)
                    LfButton("Delete", {}, style = LfButtonStyle.Inline)
                }
            }
        }
    }

    @Test
    fun threeActionCard_atDefaultScale() {
        capture("three-action-card-1x", fontScale = 1f) { ThreeActionCard() }
    }

    /**
     * The scale §9.6 requires and the one BUG9 shipped under.
     *
     * At 2.0 the three labels cannot share a line, so `LfActionRow`'s `FlowRow`
     * must move whole controls to the next line. The golden is what proves the
     * degradation is *wrapping* rather than clipping.
     */
    @Test
    fun threeActionCard_atLargestSupportedScale() {
        capture("three-action-card-2x", fontScale = 2f) { ThreeActionCard() }
    }

    @Composable
    private fun EveryButtonStyle() {
        LfActionRow(alignment = LfActionAlignment.Start) {
            LfButton("Filled", {})
            LfButton("Tonal", {}, style = LfButtonStyle.Tonal)
            LfButton("Outlined", {}, style = LfButtonStyle.Outlined)
            LfButton("Text", {}, style = LfButtonStyle.Text)
            LfButton("Inline", {}, style = LfButtonStyle.Inline)
        }
    }

    @Test
    fun everyButtonStyle_atDefaultScale() {
        capture("button-styles-1x", fontScale = 1f) { EveryButtonStyle() }
    }

    @Test
    fun everyButtonStyle_atLargestSupportedScale() {
        capture("button-styles-2x", fontScale = 2f) { EveryButtonStyle() }
    }

    /**
     * All four chip styles together.
     *
     * `Warning` and `Error` are the two that carry meaning by colour alone, so
     * they are the two a palette change can quietly break — and a palette change
     * is precisely the edit that no behavioural test notices.
     */
    @Composable
    private fun EveryChipStyle() {
        LfActionRow(alignment = LfActionAlignment.Start) {
            LfChip(label = "Assist", style = LfChipStyle.Assist)
            LfChip(label = "Selected", style = LfChipStyle.Selected)
            LfChip(label = "Warning", style = LfChipStyle.Warning)
            LfChip(label = "Error", style = LfChipStyle.Error)
        }
    }

    @Test
    fun everyChipStyle_atDefaultScale() {
        capture("chip-styles-1x", fontScale = 1f) { EveryChipStyle() }
    }

    @Test
    fun everyChipStyle_atLargestSupportedScale() {
        capture("chip-styles-2x", fontScale = 2f) { EveryChipStyle() }
    }

    /**
     * A card carrying prose, which is where BUG17's family lives.
     *
     * BUG17 was a *heading* breaking mid-word beside a control that correctly
     * refused to shrink. The layout assertion for it is
     * `Bug17_ScreenTitleNeverBreaksMidWordTest`; this is the picture of the same
     * region, so a reviewer can see whether a wrap is at a word boundary rather
     * than take a test's word for it.
     */
    @Composable
    private fun ProseCard() {
        LfCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                Text(
                    text = "Notification capture",
                    style = LfTheme.typography.titleM,
                    color = LfTheme.colors.textPrimary,
                )
                Text(
                    text = "Most UPI payments never send an SMS, so they are not " +
                        "reaching your Inbox at all.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
            }
        }
    }

    @Test
    fun proseCard_atDefaultScale() {
        capture("prose-card-1x", fontScale = 1f) { ProseCard() }
    }

    @Test
    fun proseCard_atLargestSupportedScale() {
        capture("prose-card-2x", fontScale = 2f) { ProseCard() }
    }
}

/**
 * The width BUG9 actually broke at.
 *
 * A category card on a phone, not an arbitrary small number: three `bodyL`
 * labels need roughly 300dp and the card offers about 280.
 */
private const val NARROW_CARD = 280

/** Goldens live beside the tests, committed, and are reviewed on every diff. */
private const val GOLDEN_DIR = "src/test/screenshots"

/**
 * The SDK Robolectric emulates, and it is **not** the project's `targetSdk`.
 *
 * Two independent ceilings pushed it here, and both are worth recording because
 * the next person will otherwise "fix" this back and lose an afternoon:
 *
 * 1. **Java.** Robolectric refuses SDK 36 on a Java 17 toolchain —
 *    *"Android SDK 36 requires Java 21 (have Java 17)"*. This project is on 17
 *    (SPEC.md §3) and so is CI's `setup-java`. Raising the toolchain is a
 *    whole-project decision with its own testing, not one a screenshot suite
 *    makes on the way past.
 * 2. **TLS on this dev box.** Robolectric downloads its platform jar with its
 *    *own* HTTP client rather than through Gradle, and on this machine that
 *    client cannot validate Maven Central's certificate
 *    (`SunCertPathBuilderException: unable to find valid certification path`)
 *    even though Gradle resolves from the same host without trouble. SDK 34 is
 *    already in `~/.m2`, so pinning here is also what makes the suite runnable
 *    offline. A CI runner with a stock truststore can fetch any pinned SDK, so
 *    this constraint is local — but the pin has to satisfy both.
 *
 * What it costs, precisely: these goldens render Android 14's platform
 * behaviour while the app targets 36 and compiles against 37. For *these*
 * components — buttons, chips, cards, text wrapping — that difference is nil:
 * none of them branch on API level and the layout is Compose's own. **The
 * moment a component's rendering does depend on the platform, this pin is the
 * first thing to question.**
 *
 * A literal rather than a reference to `targetSdk`, so a future bump does not
 * silently start asking Robolectric for a jar that does not exist for it.
 */
private const val ROBOLECTRIC_SDK = 34
