package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.bidiIsolated
import com.ledgerflow.core.model.LedgerType
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG61: under a right-to-left language, every English sentence put its full
 * stop at the front.**
 *
 * Found 2026-10-21 checking V2-2's Settings screen with the app's locale set to
 * Arabic: "On. Payment notifications reach .your Inbox", and the backup row
 * scrambled into "Backs up .2026/10/Last backup 21 .nightly on its own". The
 * layout mirrored correctly; the *text* did not, on every screen.
 *
 * Cause: no style set a text direction, and Compose resolves an unspecified one
 * to the layout direction. So an English sentence on a mirrored screen was laid
 * out as a right-to-left paragraph, and the bidi algorithm put its trailing
 * neutrals (the full stop, a date's slashes, a debit's leading "-") where an
 * RTL paragraph puts them.
 *
 * The fix (owner's choice, 2026-10-21) matches Android's own `TextView`: a
 * paragraph's direction comes from its first strong letter (LTR when it has
 * none, so "-₹70.00" stays one), and its *alignment* follows the screen, so
 * English on a mirrored screen still sits on the reading side. Compose measures
 * `TextAlign.Start`/`End` from the paragraph rather than the screen, so those
 * two are banned in main sources in favour of `LfTextAlign`.
 *
 * Robolectric NATIVE graphics: without it text is zero-wide and every position
 * below would pass vacuously.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class Bug61_EnglishTextKeepsItsPunctuationInRtlTest {

    @get:Rule
    val rule = createComposeRule()

    /** Lays [text] out at [WIDTH_DP], with the direction set *outside* the theme, as the activity does. */
    private fun layOut(
        text: String,
        direction: LayoutDirection,
        style: @Composable () -> TextStyle = { LfTheme.typography.bodyM },
    ): TextLayoutResult {
        var result: TextLayoutResult? = null
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                LfTheme(darkTheme = false) {
                    Text(
                        text = text,
                        style = style(),
                        modifier = Modifier.width(WIDTH_DP.dp),
                        onTextLayout = { result = it },
                    )
                }
            }
        }
        rule.waitForIdle()
        return requireNotNull(result) { "no layout for \"$text\"" }
    }

    /** True when the glyph at [offset] is the visually last one on its line, reading left to right. */
    private fun TextLayoutResult.isRightmostOnItsLine(offset: Int): Boolean {
        val line = getLineForOffset(offset)
        return abs(getBoundingBox(offset).right - getLineRight(line)) < 1f
    }

    @Test
    fun rtl_anEnglishSentence_endsWithItsFullStop() {
        val layout = layOut(SENTENCE, LayoutDirection.Rtl)

        assertThat(layout.getParagraphDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        assertWithMessage("the full stop is the last glyph read, not the first")
            .that(layout.isRightmostOnItsLine(SENTENCE.lastIndex)).isTrue()
    }

    @Test
    fun rtl_anEnglishSentence_staysOnTheReadingSideOfTheScreen() {
        val layout = layOut(SENTENCE, LayoutDirection.Rtl)
        val width = layout.size.width.toFloat()

        // Every line hugs the right edge, as a TextView's would on a mirrored
        // screen. Paragraph direction alone (the other option) puts it left.
        for (line in 0 until layout.lineCount) {
            assertWithMessage("line $line's right edge").that(layout.getLineRight(line))
                .isWithin(1f).of(width)
        }
    }

    @Test
    fun ltr_isUnchanged_leftAlignedWithItsFullStopLast() {
        val layout = layOut(SENTENCE, LayoutDirection.Ltr)

        assertThat(layout.getParagraphDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        for (line in 0 until layout.lineCount) {
            assertThat(layout.getLineLeft(line)).isWithin(1f).of(0f)
        }
        assertThat(layout.isRightmostOnItsLine(SENTENCE.lastIndex)).isTrue()
    }

    @Test
    fun rtl_aDebitKeepsItsMinusInFront() {
        // No letters at all, so "first strong letter" has nothing to go on: the
        // fallback has to be LTR, or the sign moves to the end and a debit
        // reads like a typo.
        val debit = MoneyFormat.directional(7_000L, "INR", LedgerType.DEBIT, Locale.US)
        val layout = layOut(debit, LayoutDirection.Rtl) { LfTheme.typography.amountM }

        assertThat(layout.getParagraphDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        assertWithMessage("\"$debit\": the sign is the first glyph read")
            .that(layout.getBoundingBox(0).left).isWithin(1f).of(layout.getLineLeft(0))
    }

    /**
     * Found by the LTR goldens on the way: the dirham's symbol is Arabic, so
     * "first strong letter decides" made an AED amount, and onboarding's
     * "د.إ  UAE Dirham" row, a right-to-left paragraph on an *English* screen,
     * which moved the sign and swapped the symbol and name. The symbol is now a
     * bidi isolate wherever it is built into a longer string, so it reads as one
     * unit and does not choose the paragraph's direction.
     */
    @Test
    fun ltr_aDirhamAmountStaysLeftToRightWithItsSignFirst() = assertDirhamDebitReadsLtr(LayoutDirection.Ltr)

    @Test
    fun rtl_aDirhamAmountStaysLeftToRightWithItsSignFirst() = assertDirhamDebitReadsLtr(LayoutDirection.Rtl)

    private fun assertDirhamDebitReadsLtr(direction: LayoutDirection) {
        val debit = MoneyFormat.directional(7_000L, "AED", LedgerType.DEBIT, Locale.US)
        val layout = layOut(debit, direction) { LfTheme.typography.amountM }

        assertThat(layout.getParagraphDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        assertWithMessage("\"-\" is the first glyph read")
            .that(layout.getBoundingBox(0).left).isWithin(1f).of(layout.getLineLeft(0))
    }

    @Test
    fun ltr_aSymbolLedLabelKeepsTheSymbolFirst_evenAnArabicOne() {
        val label = "${bidiIsolated("د.إ")}  UAE Dirham"
        val layout = layOut(label, LayoutDirection.Ltr)

        assertThat(layout.getParagraphDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        assertWithMessage("\"UAE Dirham\" ends the line")
            .that(layout.isRightmostOnItsLine(label.lastIndex)).isTrue()
    }

    @Test
    fun bidiIsolated_wrapsOnlyRightToLeftText() {
        assertThat(bidiIsolated("₹")).isEqualTo("₹")
        assertThat(bidiIsolated("S$")).isEqualTo("S$")
        assertThat(bidiIsolated("د.إ")).isEqualTo("\u2068د.إ\u2069")
    }

    @Test
    fun noMainSource_alignsTextByParagraphStartOrEnd() {
        // TextAlign.Start/End are measured from the paragraph's direction, which
        // is now the text's own: in RTL an English line aligned "End" would land
        // on the right, where the screen's end is the left. LfTextAlign resolves
        // against the screen instead, and is the one place allowed to name them.
        val offenders = mainSources()
            .filter { it.name != "LfTextAlign.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> PARAGRAPH_RELATIVE_ALIGN.containsMatchIn(line) }
                    .map { (index, line) -> "${file.relativeTo(repositoryRoot)}:${index + 1}: ${line.trim()}" }
            }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun mainSources_areDiscoverable() {
        assertThat(mainSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private fun mainSources(): List<File> =
        listOf("core", "feature", "app")
            .map { File(repositoryRoot, it) }
            .filter { it.isDirectory }
            .flatMap { area ->
                area.walkTopDown()
                    .onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" }
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }

    private companion object {
        const val SENTENCE = "On. Payment notifications reach your Inbox."
        const val WIDTH_DP = 300
        const val MINIMUM_EXPECTED_SOURCES = 100
        val PARAGRAPH_RELATIVE_ALIGN = Regex("""TextAlign\.(Start|End)\b""")
    }
}
