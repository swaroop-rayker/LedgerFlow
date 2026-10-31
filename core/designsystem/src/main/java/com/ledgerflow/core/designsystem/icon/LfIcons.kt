package com.ledgerflow.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icon set: one family, Lucide (step F4, ADR-0031), named by job.
 *
 * It was six icons from `material-icons-core` and a bar chart drawn here,
 * which is two families on one bar. Every icon is now Lucide's geometry from
 * [LfLucide], stroked 2 dp with round ends as the family draws it; `Icon`
 * tints the stroke. Call sites name the job ("Delete"), never the glyph, so a
 * future change of family is this file.
 *
 * Built lazily and once each: an `ImageVector` is immutable, and every caller
 * shares the instance (a list still builds one painter for all its rows,
 * `CLAUDE.md` §8).
 */
public object LfIcons {

    public val Dashboard: ImageVector by lazy { lucide("Home", LfLucide.House) }
    public val Ledger: ImageVector by lazy { lucide("Ledger", LfLucide.ListRows) }
    public val Analytics: ImageVector by lazy { lucide("Analytics", LfLucide.ChartColumn) }

    /** The Profile tab (design-system step D4, which replaced Settings in the bar). */
    public val Profile: ImageVector by lazy { lucide("Profile", LfLucide.UserRound) }

    /** Settings (V2-2), reached from Profile since design-system step D4. */
    public val Settings: ImageVector by lazy { lucide("Settings", LfLucide.Settings) }

    /** "Opens a page": `LfNavRow`'s trailing mark. Auto-mirrored, so it points onward in RTL. */
    public val Chevron: ImageVector by lazy { lucide("Chevron", LfLucide.ChevronRight, autoMirror = true) }

    /** A page's way back (step F5's top bar). Auto-mirrored, so it points back in RTL. */
    public val Back: ImageVector by lazy { lucide("Back", LfLucide.ChevronLeft, autoMirror = true) }

    /** "Opens a choice": the filter bar's period pill (step F5). */
    public val Expand: ImageVector by lazy { lucide("Expand", LfLucide.ChevronDown) }

    /** Filters (Analytics' filter sheet, step F5). */
    public val Filter: ImageVector by lazy { lucide("Filter", LfLucide.ListFilter) }

    /** Profile's Organise row: categories, merchants, payment methods, budgets (step F5). */
    public val Organise: ImageVector by lazy { lucide("Organise", LfLucide.Tag) }

    /** Profile's Data row: backup, restore, export, the bin (step F5). */
    public val Data: ImageVector by lazy { lucide("Data", LfLucide.Database) }

    /**
     * "Opens Android's settings": a row that leaves the app for a page the
     * phone owns (Data permissions, V2-3). Not mirrored: the arrow points out
     * of the box, not along the reading direction.
     */
    public val OpensSystemSettings: ImageVector by lazy { lucide("OpensSystemSettings", LfLucide.ExternalLink) }

    public val Add: ImageVector by lazy { lucide("Add", LfLucide.Plus) }
    public val Close: ImageVector by lazy { lucide("Close", LfLucide.X) }
    public val Delete: ImageVector by lazy { lucide("Delete", LfLucide.Trash) }

    private fun lucide(name: String, paths: List<String>, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = "Lf$name",
            defaultWidth = GRID.dp,
            defaultHeight = GRID.dp,
            viewportWidth = GRID,
            viewportHeight = GRID,
            autoMirror = autoMirror,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** Lucide's grid and stroke, as its SVGs declare them. */
    private const val GRID = 24f
    private const val STROKE = 2f
}
