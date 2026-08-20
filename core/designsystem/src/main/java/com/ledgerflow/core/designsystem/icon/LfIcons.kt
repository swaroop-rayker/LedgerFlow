package com.ledgerflow.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's icon set.
 *
 * Six come from `material-icons-core`, which the version catalog already
 * carries. The bar chart does not: charting icons live in
 * `material-icons-extended`, which is a very large artifact to pull in for one
 * glyph against a 15 MB budget (SPEC.md §11) that ML Kit still has to fit into
 * at P4. It is three rounded rectangles, so it is drawn here.
 *
 * Everything is routed through this object rather than referenced directly at
 * call sites, so swapping in a real icon pack later is one file.
 */
public object LfIcons {

    public val Dashboard: ImageVector = Icons.Filled.Home
    public val Ledger: ImageVector = Icons.AutoMirrored.Filled.List
    public val More: ImageVector = Icons.Filled.Menu
    public val Add: ImageVector = Icons.Filled.Add

    /** Dismiss. Never used for a destructive action without a confirmation. */
    public val Close: ImageVector = Icons.Filled.Close

    /**
     * Remove. **Always behind a confirmation** -- it sits in a scrolling list of
     * small controls, which is one mis-tap away from destroying a real entry.
     */
    public val Delete: ImageVector = Icons.Filled.Delete

    /** Three ascending bars on a 24dp grid, matching the core set's weight. */
    public val Analytics: ImageVector by lazy {
        ImageVector.Builder(
            name = "LfAnalytics",
            defaultWidth = ICON_SIZE.dp,
            defaultHeight = ICON_SIZE.dp,
            viewportWidth = ICON_SIZE,
            viewportHeight = ICON_SIZE,
        ).apply {
            bar(x = 4f, top = 13f)
            bar(x = 10.5f, top = 7f)
            bar(x = 17f, top = 10f)
        }.build()
    }

    private fun ImageVector.Builder.bar(x: Float, top: Float) {
        path(fill = SolidColor(Color.Black)) {
            moveTo(x, top)
            horizontalLineTo(x + BAR_WIDTH)
            verticalLineTo(BASELINE)
            horizontalLineTo(x)
            close()
        }
    }

    private const val ICON_SIZE = 24f
    private const val BAR_WIDTH = 3f
    private const val BASELINE = 20f
}
