package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.core.designsystem.component.GLYPH_MEASURER_CACHE_SIZE
import com.ledgerflow.core.designsystem.component.LocalLfGlyphMeasurer

/**
 * Type scale (SPEC.md §9.2).
 *
 * Every style is Inter -- the bundled variable font §9.2 asks for. See
 * [LfFontFamily] for the binary, its licence, and why each weight has to name
 * its axis explicitly.
 *
 * The amount styles carry `fontFeatureSettings = "tnum"` -- tabular figures.
 * Without it, proportional digits make a column of amounts ragged and
 * genuinely harder to compare, which is most of what this app is for.
 */
@Immutable
public data class LfTypography(
    val displayL: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val label: TextStyle,
    val amountL: TextStyle,
    val amountM: TextStyle,
    /** Monospaced-feel style for recovery words, so similar glyphs stay distinct. */
    val mnemonicWord: TextStyle,
) {
    /**
     * These styles aligned to the screen's leading edge for [direction]
     * (BUG61): left in LTR, right in RTL, whatever the text's own direction.
     * [LfTheme.typography] hands these out, so a call site gets the right edge
     * without asking. Built once per direction, not per read.
     */
    public fun alignedTo(direction: LayoutDirection): LfTypography = when (direction) {
        LayoutDirection.Ltr -> forLtr
        LayoutDirection.Rtl -> forRtl
    }

    private val forLtr: LfTypography by lazy { aligned(LfTextAlign.startOf(LayoutDirection.Ltr)) }
    private val forRtl: LfTypography by lazy { aligned(LfTextAlign.startOf(LayoutDirection.Rtl)) }

    private fun aligned(align: TextAlign): LfTypography = LfTypography(
        displayL = displayL.copy(textAlign = align),
        titleL = titleL.copy(textAlign = align),
        titleM = titleM.copy(textAlign = align),
        bodyL = bodyL.copy(textAlign = align),
        bodyM = bodyM.copy(textAlign = align),
        label = label.copy(textAlign = align),
        amountL = amountL.copy(textAlign = align),
        amountM = amountM.copy(textAlign = align),
        mnemonicWord = mnemonicWord.copy(textAlign = align),
    )
}

/** Tabular figures, so a column of amounts lines up. §9.2 makes it mandatory. */
private const val TABULAR_FIGURES = "tnum"

/**
 * Inter's `ss02`, which the font itself calls "Disambiguation": `l` gains a
 * tail, `1` a foot, `0` a slash.
 *
 * Only [LfTypography.mnemonicWord] uses it. That style has always claimed to
 * keep similar glyphs distinct, and under the platform default that was an
 * aspiration -- there was no such feature to ask for. A recovery word copied
 * wrong off the screen is the one transcription error in this app with no
 * recovery path of its own, so where the font can actually draw the difference,
 * it should.
 */
private const val DISAMBIGUATION = "ss02"

/**
 * Applied to every style so an explicit `lineHeight` cannot clip glyphs.
 *
 * Found on the device, not in a preview: at `displayL` (34sp text in a 40sp
 * line) the tops of tall ascenders were being cut off. Compose distributes a
 * tight line height from the baseline by default, so the extra space lands
 * below the text and the ascent gets trimmed. Centring the line height and
 * dropping the legacy font padding puts the slack where the glyphs actually
 * need it.
 *
 * This matters more as the font scale grows, and §9.6 requires 2.0x without
 * truncation.
 */
private val LfLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Suppress("LongParameterList")
private fun lfTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    features: List<String> = emptyList(),
): TextStyle = TextStyle(
    fontFamily = LfFontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    // Null rather than "", which some renderers treat as a feature string to
    // parse rather than as "no features".
    fontFeatureSettings = features.takeIf { it.isNotEmpty() }?.joinToString(", "),
    lineHeightStyle = LfLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    textDirection = LfTextDirection,
)

/**
 * A paragraph reads in its own text's direction (BUG61): the first strong letter
 * decides, and a string with none -- "-₹70.00", a date -- reads LTR.
 *
 * Left unset, Compose took the *screen's* direction, so on a mirrored screen an
 * English sentence was laid out right-to-left and put its full stop at the
 * front. `Content` alone would fall back to RTL there and move a debit's sign to
 * the end, hence `ContentOrLtr`; Arabic text, when there is any, still has
 * strong letters and reads RTL. Alignment is the screen's, not the text's: see
 * [LfTypography.alignedTo] and [LfTextAlign].
 */
internal val LfTextDirection: TextDirection = TextDirection.ContentOrLtr

public val LfDefaultTypography: LfTypography = LfTypography(
    displayL = lfTextStyle(34.sp, 40.sp, FontWeight.SemiBold),
    titleL = lfTextStyle(22.sp, 28.sp, FontWeight.SemiBold),
    titleM = lfTextStyle(18.sp, 24.sp, FontWeight.Medium),
    bodyL = lfTextStyle(16.sp, 24.sp),
    bodyM = lfTextStyle(14.sp, 20.sp),
    label = lfTextStyle(12.sp, 16.sp, FontWeight.Medium),
    amountL = lfTextStyle(28.sp, 32.sp, FontWeight.SemiBold, listOf(TABULAR_FIGURES)),
    amountM = lfTextStyle(18.sp, 22.sp, FontWeight.Medium, listOf(TABULAR_FIGURES)),
    mnemonicWord = lfTextStyle(
        16.sp,
        22.sp,
        FontWeight.Medium,
        listOf(TABULAR_FIGURES, DISAMBIGUATION),
    ),
)

/** Spacing scale. Nothing in the app may hardcode a dp value (CLAUDE.md §5). */
@Immutable
public data class LfSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    /** Minimum touch target (§9.6). Not negotiable. */
    val minTouchTarget: Dp = 48.dp,

    /**
     * Minimum width for a row action.
     *
     * A short label like "Delete" or "Hide" otherwise renders as a tight pill,
     * and looks especially unintended when it is the one control that wrapped
     * onto its own line beneath two wider ones. This is a *minimum*: a button
     * whose label needs more keeps its natural width, so widening can never
     * clip anything (BUG9).
     *
     * **112, not 128.** A card's inner width on a 360dp phone is ~261dp, so two
     * actions plus their 8dp gap have ~126dp each. 128 missed that by two
     * device pixels and silently stacked all three actions one per line, which
     * made the card twice as tall — a minimum width chosen at the boundary is a
     * layout that flips on a rounding error. This leaves real headroom.
     */
    val actionMinWidth: Dp = 112.dp,

    /**
     * Width one segment of `LfSegmentedControl` needs at font scale 1.0.
     *
     * Below this the control stacks its options vertically instead of dividing
     * the row. A segment cannot grow to fit its label the way a button can --
     * the cells split a fixed width -- so without the switch the label has no
     * option but to break mid-word, which is BUG9.
     *
     * Measured, not guessed: on a 360dp phone a screen-padded control gives
     * ~101dp per option across three, and "Categories" needs ~78dp of that at
     * font scale 1.15. 96 tripped at 1.15 and stacked a row that fitted
     * perfectly well; 72 keeps it horizontal there and still stacks at 2.0,
     * where the same option would need ~144dp.
     */
    val segmentMinWidth: Dp = 72.dp,

    /**
     * Width of one card in a horizontally-scrolling peek row.
     *
     * Sized so a second card is always partly visible on a 360dp screen. That
     * sliver is the entire affordance -- a row of cards that happens to fit
     * exactly reads as a complete list, and nobody swipes it.
     */
    val peekCardWidth: Dp = 172.dp,

    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 16.dp,
    val cornerLarge: Dp = 24.dp,
)

/** Motion durations (SPEC.md §9.5). */
@Immutable
public data class LfMotion(
    val micro: Int = 120,
    val standard: Int = 240,
    val emphasized: Int = 400,
    /**
     * How long an outgoing screen takes to fade out before the incoming one
     * starts to fade in (BUG57). The two never overlap: overlapping is the
     * cross-fade that drew two screens' text over each other for 700 ms.
     */
    val fadeOut: Int = 60,
    /** How far a forward/back navigation slides its screens (BUG57). */
    val axisSlide: Dp = 30.dp,
)

public val LocalLfTypography: androidx.compose.runtime.ProvidableCompositionLocal<LfTypography> =
    staticCompositionLocalOf { LfDefaultTypography }
public val LocalLfSpacing: androidx.compose.runtime.ProvidableCompositionLocal<LfSpacing> =
    staticCompositionLocalOf { LfSpacing() }
public val LocalLfMotion: androidx.compose.runtime.ProvidableCompositionLocal<LfMotion> =
    staticCompositionLocalOf { LfMotion() }

/**
 * The app theme.
 *
 * Dynamic colour (Material You) is deliberately **not** offered here. §9.1 makes
 * it opt-in only: a finance app's debit/credit colours carry meaning, and
 * letting the wallpaper repaint them would make the one thing the user must
 * read at a glance depend on their home screen.
 */
@Composable
public fun LfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) LfDarkColors else LfLightColors

    // Material components still read Material's theme; every one of its colour
    // roles and shapes is mapped from our tokens (LfMaterialTheme.kt), so a
    // Material widget never falls back to Material's own purple or 28 dp corners.
    val materialScheme = if (darkTheme) LfDarkColorScheme else LfLightColorScheme

    CompositionLocalProvider(
        LocalLfColors provides colors,
        LocalLfTypography provides LfDefaultTypography,
        LocalLfSpacing provides LfSpacing(),
        LocalLfMotion provides LfMotion(),
        LocalLfGlyphMeasurer provides rememberTextMeasurer(cacheSize = GLYPH_MEASURER_CACHE_SIZE),
    ) {
        // Typography as well as colours, for the same reason: a Material widget
        // that renders its own text would otherwise come out in the platform
        // font on a screen that is otherwise Inter. See [LfMaterialTypography].
        // Aligned to the screen's direction here, at the root, because Material
        // reads its typography directly rather than through [LfTheme.typography].
        MaterialTheme(
            colorScheme = materialScheme,
            shapes = LfMaterialShapes,
            typography = lfMaterialTypographyFor(LocalLayoutDirection.current),
            content = content,
        )
    }
}

/** Token accessors. `LfTheme.colors.debit`, never a hardcoded hex. */
public object LfTheme {
    public val colors: LfColors
        @Composable get() = LocalLfColors.current

    /** Aligned to the current layout direction's leading edge (BUG61). */
    public val typography: LfTypography
        @Composable get() = LocalLfTypography.current.alignedTo(LocalLayoutDirection.current)

    public val spacing: LfSpacing
        @Composable get() = LocalLfSpacing.current

    public val motion: LfMotion
        @Composable get() = LocalLfMotion.current
}
