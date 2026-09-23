package com.ledgerflow.core.ui.lineitem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §12's screenshot gate for `LfLineItemEditor`.
 *
 * ## Why this did not exist, and why that mattered
 *
 * The Roborazzi harness lived only in `:core:designsystem`. `:core:ui` depends
 * on that module, so the harness could not reach back into it — and
 * `LfLineItemEditor`, which is the component `SPEC.md` §5.3's receipt review
 * and §5.4's itemised entry are both built on, had **no golden at any font
 * scale**.
 *
 * That is the wrong thing to leave outside the gate. It renders a variable-length
 * name beside an amount that must never be abbreviated, a filing line that is
 * absent when nothing is filed, and a running reconciliation that changes
 * colour — every one of those is a BUG5 (font scale 2.0) or BUG9 (label
 * wrapping) shape, and both of those bugs shipped precisely because previews
 * were not being diffed.
 *
 * Found while mocking a redesign of this row: the redesign was being judged
 * against drawings while the real component had no picture at all.
 *
 * ## The cases
 *
 * Chosen for what breaks a row, not for coverage:
 *
 * - **A filed line** — the ordinary case, and the baseline any change is read
 *   against.
 * - **An unfiled line** — "No category" in tertiary, which is the only signal
 *   the user has that a row needs attention.
 * - **A long name** — must ellipsise, never wrap, and must not push the amount
 *   off the row. The amount is the one thing on the line that may not be
 *   abbreviated.
 * - **A fractional quantity** — `×0.5` is the case `quantity_milli` exists for,
 *   and it lengthens the second line where a chip or a label would collide.
 * - **Unbalanced** — the header's delta in `warn`, which §5.4 requires to be
 *   shown rather than corrected.
 *
 * All five at 1.0 and 2.0, in a narrow container. The narrow container is the
 * point: at full phone width this component is comfortable, and the interesting
 * behaviour is what happens when it is not.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12). A suite whose
 * failure mode is "run record again" asserts nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [EDITOR_ROBOLECTRIC_SDK])
class LfLineItemEditorScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, fontScale: Float, state: LineItemEditorState) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LfTheme {
                    Column(
                        modifier = Modifier
                            .width(NARROW_EDITOR.dp)
                            .padding(LfTheme.spacing.md),
                    ) {
                        LfLineItemEditor(state = state, onEvent = {})
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$EDITOR_GOLDEN_DIR/$name.png", roborazziOptions = LfScreenshotOptions)
    }

    private fun row(
        key: String,
        name: String,
        total: String,
        quantityLabel: String? = null,
        category: String? = null,
        subcategory: String? = null,
    ) = LineItemRow(
        key = key,
        name = name,
        totalText = total,
        quantityLabel = quantityLabel,
        categoryName = category,
        subcategoryName = subcategory,
    )

    /** The ordinary receipt: a filed line, an unfiled one, a fractional quantity. */
    private fun balancedState() = LineItemEditorState(
        rows = listOf(
            row("a", "Rice Sona Masoori 5kg", "₹420.00", category = "Groceries", subcategory = "Staples"),
            row("b", "Tomato", "₹20.00", quantityLabel = "×0.5", category = "Groceries"),
            row("c", "Amul Taaza Toned Milk 500ml", "₹54.00", quantityLabel = "×2"),
        ),
        summary = "All allocated",
        balanced = true,
    )

    /** §5.4's saveable-but-unbalanced set, which the header must state plainly. */
    private fun unbalancedState() = LineItemEditorState(
        rows = listOf(
            row("a", "Rice Sona Masoori 5kg", "₹420.00", category = "Groceries"),
        ),
        summary = "₹53.00 left",
        balanced = false,
    )

    @Test fun balanced_1x() = capture("line-editor-balanced-1x", 1f, balancedState())

    @Test fun balanced_2x() = capture("line-editor-balanced-2x", 2f, balancedState())

    @Test fun unbalanced_1x() = capture("line-editor-unbalanced-1x", 1f, unbalancedState())

    @Test fun unbalanced_2x() = capture("line-editor-unbalanced-2x", 2f, unbalancedState())

    /** An empty editor still offers the one action that gets you out of it. */
    @Test
    fun empty_1x() = capture(
        "line-editor-empty-1x",
        1f,
        LineItemEditorState(rows = emptyList(), summary = null, balanced = true),
    )

    @Test
    fun empty_2x() = capture(
        "line-editor-empty-2x",
        2f,
        LineItemEditorState(rows = emptyList(), summary = null, balanced = true),
    )
}

private const val EDITOR_ROBOLECTRIC_SDK = 34
private const val NARROW_EDITOR = 320
private const val EDITOR_GOLDEN_DIR = "src/test/screenshots"
