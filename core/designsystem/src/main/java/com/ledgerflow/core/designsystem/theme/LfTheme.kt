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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.ledgerflow.core.designsystem.component.GLYPH_MEASURER_CACHE_SIZE
import com.ledgerflow.core.designsystem.theme.primitive.LfScale
import com.ledgerflow.core.designsystem.theme.primitive.LfTypeScale
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

    /** These styles by job ([LfTypeRoles]); built once, so `LfTheme.type` costs no allocation per read. */
    internal val roles: LfTypeRoles by lazy { toRoles() }

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
    family: FontFamily = LfFontFamily,
): TextStyle = TextStyle(
    fontFamily = family,
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
    displayL = lfTextStyle(LfTypeScale.Size34, LfTypeScale.Line40, FontWeight.SemiBold),
    titleL = lfTextStyle(LfTypeScale.Size22, LfTypeScale.Line28, FontWeight.SemiBold),
    titleM = lfTextStyle(LfTypeScale.Size18, LfTypeScale.Line24, FontWeight.Medium),
    bodyL = lfTextStyle(LfTypeScale.Size16, LfTypeScale.Line24),
    bodyM = lfTextStyle(LfTypeScale.Size14, LfTypeScale.Line20),
    label = lfTextStyle(LfTypeScale.Size12, LfTypeScale.Line16, FontWeight.Medium),
    // IBM Plex Mono (ADR-0031): monospaced, so a column of amounts aligns on its
    // own. `tnum` stays, so the figures hold if a style is ever set in Inter.
    amountL = lfTextStyle(
        LfTypeScale.Size28,
        LfTypeScale.Line32,
        FontWeight.SemiBold,
        listOf(TABULAR_FIGURES),
        LfFigureFontFamily,
    ),
    amountM = lfTextStyle(
        LfTypeScale.Size18,
        LfTypeScale.Line22,
        FontWeight.Medium,
        listOf(TABULAR_FIGURES),
        LfFigureFontFamily,
    ),
    mnemonicWord = lfTextStyle(
        LfTypeScale.Size16,
        LfTypeScale.Line22,
        FontWeight.Medium,
        listOf(TABULAR_FIGURES, DISAMBIGUATION),
    ),
)

/** Motion durations (SPEC.md §9.5). Already roles by job; the values come from the primitive scale. */
@Immutable
public data class LfMotion(
    val micro: Int = LfScale.Duration120,
    val standard: Int = LfScale.Duration240,
    val emphasized: Int = LfScale.Duration400,
    /**
     * How long an outgoing screen takes to fade out before the incoming one
     * starts to fade in (BUG57). The two never overlap: overlapping is the
     * cross-fade that drew two screens' text over each other for 700 ms.
     */
    val fadeOut: Int = LfScale.Duration60,
    /** How far a forward/back navigation slides its screens (BUG57). */
    val axisSlide: Dp = LfScale.Slide30,
)

public val LocalLfTypography: androidx.compose.runtime.ProvidableCompositionLocal<LfTypography> =
    staticCompositionLocalOf { LfDefaultTypography }
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
        LocalLfSpaceRoles provides LfDefaultSpaceRoles,
        LocalLfBorderRoles provides LfDefaultBorderRoles,
        LocalLfShapeRoles provides LfDefaultShapeRoles,
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

/**
 * Token accessors: semantic roles only (DESIGN_SYSTEM_PLAN §4.3).
 * `LfTheme.colors.money.outgoing`, `LfTheme.type.body`, `LfTheme.space.screenEdge`,
 * `LfTheme.shapes.container`, never a hard-coded hex, size or radius. The raw values behind the roles are
 * `internal` to this module.
 */
public object LfTheme {
    /** Colour roles: `surface`, `text`, `border`, `action`, `money`, `status`, `chart`. */
    public val colors: LfColors
        @Composable get() = LocalLfColors.current

    /** Text styles by job, aligned to the current layout direction's leading edge (BUG61). */
    public val type: LfTypeRoles
        @Composable get() = LocalLfTypography.current.alignedTo(LocalLayoutDirection.current).roles

    /** Space by job. */
    public val space: LfSpaceRoles
        @Composable get() = LocalLfSpaceRoles.current

    /** Stroke widths by job; their colours are in [colors]`.border`. */
    public val borders: LfBorderRoles
        @Composable get() = LocalLfBorderRoles.current

    /** Corner shapes by job. Layout constants, which no style changes, are in [LfLayout]. */
    public val shapes: LfShapeRoles
        @Composable get() = LocalLfShapeRoles.current

    public val motion: LfMotion
        @Composable get() = LocalLfMotion.current
}
