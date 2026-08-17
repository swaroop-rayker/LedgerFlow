package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Type scale (SPEC.md §9.2).
 *
 * The amount styles carry `fontFeatureSettings = "tnum"` -- tabular figures.
 * Without it, proportional digits make a column of amounts ragged and
 * genuinely harder to compare, which is most of what this app is for.
 *
 * Note: §9.2 specifies a bundled variable font (Inter or Manrope). That is not
 * wired yet -- the font binary has to be added to the repo and the licence
 * recorded. Until then this uses the platform default, which still honours
 * "tnum" on Android. Tracked as a Step 6 follow-up.
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
)

private const val TABULAR_FIGURES = "tnum"

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
    tabularFigures: Boolean = false,
): TextStyle = TextStyle(
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    fontFeatureSettings = if (tabularFigures) TABULAR_FIGURES else null,
    lineHeightStyle = LfLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

public val LfDefaultTypography: LfTypography = LfTypography(
    displayL = lfTextStyle(34.sp, 40.sp, FontWeight.SemiBold),
    titleL = lfTextStyle(22.sp, 28.sp, FontWeight.SemiBold),
    titleM = lfTextStyle(18.sp, 24.sp, FontWeight.Medium),
    bodyL = lfTextStyle(16.sp, 24.sp),
    bodyM = lfTextStyle(14.sp, 20.sp),
    label = lfTextStyle(12.sp, 16.sp, FontWeight.Medium),
    amountL = lfTextStyle(28.sp, 32.sp, FontWeight.SemiBold, tabularFigures = true),
    amountM = lfTextStyle(18.sp, 22.sp, FontWeight.Medium, tabularFigures = true),
    mnemonicWord = lfTextStyle(16.sp, 22.sp, FontWeight.Medium, tabularFigures = true),
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

    // Material components still need a ColorScheme; map our tokens onto it so a
    // stray Material widget inherits the right surface instead of purple.
    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.surfaceBase,
            onBackground = colors.textPrimary,
            surface = colors.surfaceRaised,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceOverlay,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            error = colors.debit,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.surfaceBase,
            onBackground = colors.textPrimary,
            surface = colors.surfaceRaised,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceOverlay,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            error = colors.debit,
        )
    }

    CompositionLocalProvider(
        LocalLfColors provides colors,
        LocalLfTypography provides LfDefaultTypography,
        LocalLfSpacing provides LfSpacing(),
        LocalLfMotion provides LfMotion(),
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

/** Token accessors. `LfTheme.colors.debit`, never a hardcoded hex. */
public object LfTheme {
    public val colors: LfColors
        @Composable get() = LocalLfColors.current

    public val typography: LfTypography
        @Composable get() = LocalLfTypography.current

    public val spacing: LfSpacing
        @Composable get() = LocalLfSpacing.current

    public val motion: LfMotion
        @Composable get() = LocalLfMotion.current
}
