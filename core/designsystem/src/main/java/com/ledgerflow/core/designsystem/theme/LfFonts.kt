package com.ledgerflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Material's own type scale, in Inter.
 *
 * Every `Lf*` component passes an explicit [LfTypography] style, so this reaches
 * only text a Material component renders on its own account — a `Snackbar`
 * message, an `AlertDialog` that skips our slots. Without it those come out in
 * the platform default while the rest of the screen is Inter: latent today (no
 * screen passes a `snackbarHostState` yet) and shipped the first time one does.
 *
 * Sizes are Material's, deliberately. This is a floor under stray widgets, not a
 * second type scale competing with §9.2's.
 *
 * `Typography` has no public `copy()` in Material3 1.4.0 — only the
 * fifteen-argument constructor — so the mapping is written out. `LfFontsTest`
 * reflects over the result and fails if a style was missed, including one a
 * future BOM adds.
 */
internal val LfMaterialTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = LfFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = LfFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = LfFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = LfFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = LfFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = LfFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = LfFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = LfFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = LfFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = LfFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = LfFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = LfFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = LfFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = LfFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = LfFontFamily),
    )
}
