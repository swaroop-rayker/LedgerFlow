package com.ledgerflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.ledgerflow.core.designsystem.R

/**
 * Every weight cut out of the one variable file, paired with the XML that cuts
 * it.
 *
 * The two halves must agree, which is why they are one list rather than two.
 *
 * **400/500/600 are the type scale's; 700/800/900 exist for the accessibility
 * adjustment** and are deliberately not named by any [LfTypography] style. See
 * [LfFontFamily].
 */
private val InterCuts: List<Pair<FontWeight, Int>> = listOf(
    FontWeight.Normal to R.font.inter_regular,
    FontWeight.Medium to R.font.inter_medium,
    FontWeight.SemiBold to R.font.inter_semibold,
    FontWeight.Bold to R.font.inter_bold,
    FontWeight.ExtraBold to R.font.inter_extrabold,
    FontWeight.Black to R.font.inter_black,
)

/**
 * The weights the family can render.
 *
 * A style naming a weight outside this list does not fail: Compose picks the
 * nearest registered entry and synthesizes the difference, invisibly.
 * `LfFontsTest` fails the build instead.
 */
public val LfFontWeights: List<FontWeight> = InterCuts.map { it.first }

/**
 * Inter, bundled (SPEC.md §9.2). The app's one family; nothing may reach for
 * `FontFamily.Default`.
 *
 * **Bundled, never downloaded.** §9.2 says so outright, and Law 6 settles it
 * anyway: no build carries `INTERNET`, so Compose's downloadable-font provider
 * could not fetch anything even if it were wired. The binary is
 * `res/font/inter_variable.ttf` — `InterVariable.ttf` from the Inter 4.1
 * release, unmodified. One file covers every weight below.
 *
 * **The licence ships in `assets/`, not `res/raw/`, and that placement is
 * load-bearing.** OFL 1.1 §2 requires the licence and copyright notice to
 * travel with every copy of the font, and an APK is a copy. Release builds set
 * `isShrinkResources = true`, so a raw resource that no code references is
 * stripped — the font would ship and the licence would not, in release only,
 * with nothing failing anywhere. Assets are never shrunk. The file is
 * `assets/licenses/Inter-OFL.txt`; a Settings → Licences screen can read it
 * from there when one exists.
 *
 * ## Why each weight goes through an XML `<font-family>`
 *
 * The obvious way to cut a weight out of a variable file is Compose's
 * `Font(resId, weight, style, variationSettings)`. It applies the axis, but the
 * result is not the master Inter actually draws — it comes out systematically
 * heavier, because Compose is still synthesizing on top of the varied instance.
 * Declaring the axis in `res/font/inter_*.xml`, where the platform's own
 * font-family parser applies it, gives metrics **identical** to Inter's shipped
 * static instances, which is the ground truth for "this is the real master".
 *
 * Advance widths for one sample string, measured on SM-S721B with the weight
 * adjustment below neutralized:
 *
 * ```
 *                                       400   500   600
 * Inter's shipped static instances      862   872   886   <- ground truth
 * XML font-family cuts (this file)      862   872   886   <- identical
 * variationSettings on a Compose Font   898   913   931   <- heavier; matches
 *                                                            no real master
 * ```
 *
 * `LfFontAxisTest` is that measurement, kept as a regression test.
 *
 * ## Why 700, 800 and 900 are registered when no style asks for them
 *
 * Android's "Bold text" accessibility setting is a `fontWeightAdjustment` that
 * Compose **adds to every requested weight** before matching. On the device this
 * was developed against it is `+300`. With only 400/500/600 registered, the
 * scale's three weights arrive as 700/800/900, every one of them matches the
 * heaviest entry available, and the whole app flattens to a single weight — the
 * type hierarchy §9.2 specifies just disappears for the users who most need the
 * text heavier.
 *
 * Registering the shifted counterparts costs three more XML files and not one
 * byte of binary, and the hierarchy survives the adjustment. This is why the
 * list is longer than the type scale, and it is not dead weight to prune.
 *
 * ## Why `opsz` is left alone
 *
 * Inter 4.x also carries an optical-size axis (14–32); naming only `wght` in the
 * XML leaves it at its default of 14, the Text cut. Driving it from the rendered
 * size would change the letterforms as the user's font scale grows, so the app
 * would be one typeface at scale 1.0 and a different one at 2.0. §9.6 asks for
 * the same design to survive 2.0, not to become a second design.
 *
 * ## What it does not cover
 *
 * Neither Inter nor any candidate considered carries Arabic, so AED's "د.إ"
 * falls back to the system font. That is the platform doing the right thing
 * with a missing glyph, not a defect to chase.
 */
public val LfFontFamily: FontFamily = FontFamily(
    InterCuts.map { (weight, resId) -> Font(resId = resId, weight = weight) },
)

/**
 * IBM Plex Mono's four static cuts (ADR-0031), each registered at the weight it
 * draws, and Bold registered again at 800 and 900.
 *
 * Plex Mono ships no variable file and no cut heavier than Bold. The repeats
 * are for the same accessibility adjustment as Inter's 700/800/900 (see
 * [LfFontFamily]): with "Bold text" on, a 500 request arrives as 800, and with
 * nothing registered there Compose would *synthesize* a heavier Bold. Pointing
 * 800 and 900 at the real Bold keeps the glyphs the font's own. The cost of
 * having no heavier master is that, under that setting, amounts all draw at
 * Bold; the words around them, in Inter, keep their hierarchy.
 */
private val PlexMonoCuts: List<Pair<FontWeight, Int>> = listOf(
    FontWeight.Normal to R.font.ibm_plex_mono_regular,
    FontWeight.Medium to R.font.ibm_plex_mono_medium,
    FontWeight.SemiBold to R.font.ibm_plex_mono_semibold,
    FontWeight.Bold to R.font.ibm_plex_mono_bold,
    FontWeight.ExtraBold to R.font.ibm_plex_mono_bold,
    FontWeight.Black to R.font.ibm_plex_mono_bold,
)

/** The weights [LfFigureFontFamily] answers without synthesizing one. */
public val LfFigureFontWeights: List<FontWeight> = PlexMonoCuts.map { it.first }

/**
 * IBM Plex Mono, bundled (SPEC.md §9.2, ADR-0031): the face for amounts, and
 * from F5 for dates and Home's status line. Monospaced, so figures align
 * without depending on `tnum`.
 *
 * Bundled like Inter and for the same reasons (Law 6). The four files are
 * `IBMPlexMono-{Regular,Medium,SemiBold,Bold}.ttf` from `google/fonts`
 * (`ofl/ibmplexmono`), unmodified, renamed only because resource names must be
 * lower-case; about 550 KB together. Static cuts, so they are registered
 * directly rather than through XML: there is no axis for a Compose `Font` to
 * mis-apply. The licence is `assets/licenses/IBMPlexMono-OFL.txt`, in
 * `assets/` for the reason Inter's is.
 *
 * It has ₹, €, £, ¥, `$` and the minus sign. Like Inter it has no Arabic, so
 * AED's "د.إ" and Arabic-Indic digits fall back to the system font.
 */
public val LfFigureFontFamily: FontFamily = FontFamily(
    PlexMonoCuts.map { (weight, resId) -> Font(resId = resId, weight = weight) },
)

/**
 * Material's own type scale, in Inter.
 *
 * Every `Lf*` component passes an explicit `LfTheme.type` style, so this reaches
 * only text a Material component renders on its own account — a `Snackbar`
 * message (the window's one snackbar, `LfFeedback`, and the Inbox's Undo), an
 * `AlertDialog` that skips our slots. Without it those come out in the
 * platform default while the rest of the screen is Inter.
 *
 * Sizes are Material's, deliberately. This is a floor under stray widgets, not a
 * second type scale competing with §9.2's.
 *
 * `Typography` has no public `copy()` in Material3 1.4.0 — only the
 * fifteen-argument constructor — so the mapping is written out. `LfFontsTest`
 * reflects over the result and fails if a style was missed, including one a
 * future BOM adds.
 */
internal val LfMaterialTypography: Typography =
    // The paragraph takes its text's direction, as every LfTypography style
    // does (BUG61).
    Typography().mapStyles { it.copy(fontFamily = LfFontFamily, textDirection = LfTextDirection) }

/** [LfMaterialTypography] aligned to each direction's leading edge (BUG61), built once. */
private val LfMaterialTypographyLtr: Typography =
    LfMaterialTypography.mapStyles { it.copy(textAlign = LfTextAlign.startOf(LayoutDirection.Ltr)) }
private val LfMaterialTypographyRtl: Typography =
    LfMaterialTypography.mapStyles { it.copy(textAlign = LfTextAlign.startOf(LayoutDirection.Rtl)) }

/** The Material type scale [LfTheme] provides under [direction]. */
internal fun lfMaterialTypographyFor(direction: LayoutDirection): Typography = when (direction) {
    LayoutDirection.Ltr -> LfMaterialTypographyLtr
    LayoutDirection.Rtl -> LfMaterialTypographyRtl
}

private fun Typography.mapStyles(transform: (TextStyle) -> TextStyle): Typography = Typography(
    displayLarge = transform(displayLarge),
    displayMedium = transform(displayMedium),
    displaySmall = transform(displaySmall),
    headlineLarge = transform(headlineLarge),
    headlineMedium = transform(headlineMedium),
    headlineSmall = transform(headlineSmall),
    titleLarge = transform(titleLarge),
    titleMedium = transform(titleMedium),
    titleSmall = transform(titleSmall),
    bodyLarge = transform(bodyLarge),
    bodyMedium = transform(bodyMedium),
    bodySmall = transform(bodySmall),
    labelLarge = transform(labelLarge),
    labelMedium = transform(labelMedium),
    labelSmall = transform(labelSmall),
)
