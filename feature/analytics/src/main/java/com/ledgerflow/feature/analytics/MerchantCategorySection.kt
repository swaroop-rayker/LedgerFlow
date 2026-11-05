package com.ledgerflow.feature.analytics

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ledgerflow.core.designsystem.chart.LfCategoryPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.MerchantCategoryBreakdown

/**
 * A11 - by merchant category (V2-PLAN §5.4, V2-6 PR 4): where the money went by
 * kind of place, beside "By category" (what it was for).
 *
 * The same shape as A2, on purpose: donut or treemap over the ranked list, and
 * a tap lists the bucket's merchants under it, as a category lists its
 * subcategories. Uncategorised and No merchant come last, and the rows add up
 * to the page total. Under Uncategorised, how many merchants are still to file
 * and the way to file them.
 */
internal fun LazyListScope.merchantCategorySection(
    state: AnalyticsUiState,
    snapshot: AnalyticsSnapshot,
    onEvent: (AnalyticsEvent) -> Unit,
    links: MerchantLinks,
) {
    val breakdown = snapshot.merchantCategories
    if (breakdown.totals.isEmpty()) return
    item(key = "merchant-categories", contentType = "chart") {
        SectionCard(title = "By merchant category") {
            CategoryBreakdown(
                totals = breakdown.totals,
                currency = state.baseCurrency,
                expandedId = state.expandedMerchantCategoryId,
                subcategories = breakdown.merchants,
                treemapShown = state.merchantTreemapShown,
                onExpand = { onEvent(AnalyticsEvent.MerchantCategoryExpanded(it)) },
                onToggleTreemap = { onEvent(AnalyticsEvent.MerchantTreemapToggled) },
                afterRow = { total ->
                    if (total.id == MerchantCategoryBreakdown.UNCATEGORISED_ID) {
                        UncategorisedLink(breakdown.merchants[total.id].orEmpty().size, links.categorise)
                    }
                },
                // The last drill step (V2-6): a merchant's entries for this book and window.
                onChildClick = { merchant -> links.openMerchant(merchant.id, merchant.name) },
            )
        }
    }
}

/** Where A11 leads out of Analytics: Organise to file merchants, and a merchant's entries. */
internal class MerchantLinks(
    val categorise: () -> Unit,
    val openMerchant: (id: String, name: String) -> Unit,
)

/**
 * How many merchants are still to file, and the way to file them (§5.4:
 * "Uncategorised is a bucket, not a hole"): Organise > Merchants on its
 * Uncategorised filter. Inline, on one edge: it belongs to the row above, not
 * to the card.
 *
 * A plain centred row, not an `LfActionRow`: that is for controls, and its
 * top-aligned flow left the sentence riding above the button's middle. Here
 * the sentence may wrap at font scale 2.0; the button keeps its one line (BUG9).
 */
@Composable
private fun UncategorisedLink(merchants: Int, onCategoriseMerchants: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
    ) {
        Text(
            text = if (merchants == 1) "1 merchant has no category" else "$merchants merchants have no category",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
            modifier = Modifier.weight(1f, fill = false),
        )
        // "Categorise", not "Categorise in Merchants": the long label needed
        // 280 dp at font scale 2.0 inside a 264 dp card (BUG9's guard).
        LfButton(
            text = "Categorise",
            style = LfButtonStyle.Inline,
            onClick = onCategoriseMerchants,
        )
    }
}

/**
 * A row's colour in A2 and A11. The palette, except A11's Uncategorised,
 * which is a bucket rather than a category and takes the darker neutral,
 * beside No merchant's lighter one (`''`, the palette's own unfiled colour),
 * so the two unfiled slices never wear a category's hue or each other's. The
 * first recording had both near the same grey, which read as one slice.
 */
@Composable
internal fun dimensionColor(id: String, colorArgb: Int?): Color =
    if (id == MerchantCategoryBreakdown.UNCATEGORISED_ID) {
        LfTheme.colors.text.secondary
    } else {
        LfCategoryPalette.colorForId(id, colorArgb)
    }
