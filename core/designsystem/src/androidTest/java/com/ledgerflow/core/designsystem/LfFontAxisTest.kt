package com.ledgerflow.core.designsystem

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfFontFamily
import com.ledgerflow.core.designsystem.theme.LfFontWeights
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every registered weight of the bundled font renders its own master
 * (SPEC.md §9.2).
 *
 * This is the one claim about Inter that nothing else can check. The whole
 * family is cut from a single variable file, and the failure mode is silent: get
 * the cutting wrong and Compose loads one instance for every entry, then fakes
 * the rest by smearing the outline. It renders, it looks approximately right,
 * and the type scale quietly has one real weight instead of six.
 *
 * Advance width is the tell. Inter's masters get progressively wider, so the
 * same string measures strictly wider at each weight. Collapse the cutting and
 * the numbers repeat.
 *
 * **The weight adjustment has to be neutralized, and that is not a technicality.**
 * Android's "Bold text" accessibility setting is a `fontWeightAdjustment` that
 * Compose adds to every requested weight *before* matching a font. It is `+300`
 * on the device this was written against, which means an un-neutralized run of
 * this test measures the device's accessibility setting rather than the font,
 * and reports every weight as identical. That is not the font being broken —
 * it is the platform doing exactly what the user asked for. The setting is the
 * user's; the test works around it and never writes to it.
 *
 * Instrumented rather than JVM: this asserts on a real `TextLayoutResult` from
 * the real font file, which is the whole point. Nothing here touches a vault.
 */
@RunWith(AndroidJUnit4::class)
class LfFontAxisTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        /**
         * Letters whose masters differ most across weights, plus digits, at a
         * size where a real difference clears sub-pixel rounding.
         */
        const val SAMPLE = "Groceries 1,240.00"

        fun tagFor(weight: FontWeight): String = "sample-${weight.weight}"
    }

    /**
     * Every registered weight in one composition. `setContent` may be called
     * only once per test, and rendering them together is closer to the real case
     * anyway.
     */
    private fun renderEveryWeight() {
        composeRule.setContent {
            WithoutWeightAdjustment {
                Column {
                    LfFontWeights.forEach { weight ->
                        Text(
                            text = SAMPLE,
                            style = TextStyle(
                                fontFamily = LfFontFamily,
                                fontWeight = weight,
                                fontSize = 32.sp,
                            ),
                            modifier = Modifier.testTag(tagFor(weight)),
                        )
                    }
                }
            }
        }
    }

    /** See the class note. Reads the setting, never writes it. */
    @Composable
    private fun WithoutWeightAdjustment(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val unadjusted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val configuration = Configuration(context.resources.configuration)
            configuration.fontWeightAdjustment = 0
            context.createConfigurationContext(configuration)
        } else {
            // The setting does not exist below API 31, so there is nothing to
            // undo and the device's own resolver is already the right one.
            context
        }
        CompositionLocalProvider(
            LocalFontFamilyResolver provides createFontFamilyResolver(unadjusted),
            content = content,
        )
    }

    private fun renderedWidthOf(weight: FontWeight): Int {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(tagFor(weight)).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.first().size.width
    }

    @Test
    fun everyRegisteredWeightRendersItsOwnMaster() {
        renderEveryWeight()

        val widths = LfFontWeights.map { it to renderedWidthOf(it) }
        // A guard on the guard: with no samples every assertion below passes
        // vacuously.
        assertThat(widths).isNotEmpty()

        widths.zipWithNext { (lighterWeight, lighter), (heavierWeight, heavier) ->
            assertThat(lighterWeight.weight).isLessThan(heavierWeight.weight)
            // Strictly wider, not merely different: equal widths are the cutting
            // collapsing, and a *narrower* heavier weight would mean the family
            // is resolving its entries in the wrong order.
            assertThat(heavier).isGreaterThan(lighter)
        }
    }

    /**
     * The registered set reaches far enough that the accessibility adjustment
     * still has somewhere to land.
     *
     * With only the type scale's 400/500/600 registered, a `+300` adjustment
     * sends all three to the heaviest entry and the app renders in one weight —
     * the hierarchy §9.2 specifies disappears for exactly the users who turned
     * the setting on. This asserts the shifted weights still resolve to distinct
     * masters, which is what the 700/800/900 cuts exist for.
     */
    @Test
    fun theScalesWeightsStayDistinctUnderTheBoldTextAdjustment() {
        val adjustment = 300
        val shifted = LfFontWeights
            .filter { it.weight + adjustment <= FontWeight.Black.weight }
            .map { FontWeight(it.weight + adjustment) }

        assertThat(shifted).isNotEmpty()
        assertThat(LfFontWeights).containsAtLeastElementsIn(shifted)
    }
}
