package com.ledgerflow.feature.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.chart.LfBarColumn
import com.ledgerflow.core.designsystem.chart.LfBarDatum
import com.ledgerflow.core.designsystem.chart.LfBarSegment
import com.ledgerflow.core.designsystem.chart.LfCategoryPalette
import com.ledgerflow.core.designsystem.chart.LfDonutChart
import com.ledgerflow.core.designsystem.chart.LfDonutSlice
import com.ledgerflow.core.designsystem.chart.LfHorizontalBarChart
import com.ledgerflow.core.designsystem.chart.LfStackedBarChart
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.DimensionTotal

/**
 * Analytics — A1 through A5 (`SPEC.md` §5.6).
 *
 * **One card shape for every section**, per `CLAUDE.md`'s "one shape per
 * screen": five different containers would read as five designs stacked, and
 * that is obvious the moment the user scrolls.
 *
 * **In every section the ranked list is the content and the chart is
 * orientation.** The donut is small and the list beneath it carries the figures,
 * because that is what the user came to read — a chart sized to fill the
 * viewport would push the numbers off screen to display less information.
 *
 * **A `LazyColumn` with `key` and `contentType`** (`CLAUDE.md` §5), because the
 * merchant and category lists are unbounded.
 */
@Composable
public fun AnalyticsScreen(
    state: AnalyticsUiState,
    onEvent: (AnalyticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = PaddingValues(bottom = LfTheme.spacing.xl),
    ) {
        item(key = "title", contentType = "title") {
            LfScreenTitle(title = "Analytics")
        }
        item(key = "ranges", contentType = "ranges") {
            RangeRow(selected = state.range, onEvent = onEvent)
        }

        val snapshot = state.snapshot
        if (state.showEmptyState || snapshot == null) {
            emptySection(state)
        } else {
            chartSections(state, snapshot, onEvent)
        }
    }
}

/**
 * "Nothing yet" and "nothing here" are different, and the copy says which.
 *
 * A vault still loading and a vault with no entries look identical if the
 * screen only checks for an empty snapshot, and the first would render as a
 * flash of "no spending" before the real numbers arrived.
 */
private fun LazyListScope.emptySection(state: AnalyticsUiState) {
    item(key = "empty", contentType = "empty") {
        LfEmptyState(
            title = if (state.isLoading) "Loading" else "Nothing to chart yet",
            body = if (state.isLoading) {
                "Reading your ledger."
            } else {
                "Approve a transaction and it will show up here."
            },
        )
    }
}

/**
 * A1, A2/A3, A5 and A4, in that order.
 *
 * The order is the reading order rather than the numbering: the time chart
 * answers "how am I doing", the category breakdown answers "on what", and the
 * merchant leaderboard is the detail someone scrolls for. Split out of
 * [AnalyticsScreen] so each section is separately readable — and because a
 * single composable holding all five sections is exactly the shape that grows
 * until nobody can see the whole of it.
 */
private fun LazyListScope.chartSections(
    state: AnalyticsUiState,
    snapshot: AnalyticsSnapshot,
    onEvent: (AnalyticsEvent) -> Unit,
) {
    item(key = "total", contentType = "total") {
        TotalCard(snapshot = snapshot, currency = state.baseCurrency)
    }

    // A1 — spend over time.
    item(key = "over-time", contentType = "chart") {
        SectionCard(title = "Spend over time") {
            LfStackedBarChart(
                columns = snapshot.toColumns(),
                formatAxisValue = { minor -> MoneyFormat.symbolised(minor, state.baseCurrency) },
            )
        }
    }

    // A2 — category breakdown, with A3's drill-down inside it.
    item(key = "categories", contentType = "chart") {
        SectionCard(title = "By category") {
            DonutWithList(
                totals = snapshot.categories,
                currency = state.baseCurrency,
                expandedId = state.expandedCategoryId,
                subcategories = snapshot.subcategories,
                onExpand = { onEvent(AnalyticsEvent.CategoryExpanded(it)) },
            )
        }
    }

    // A5 — payment method split. The same donut, different data.
    item(key = "methods", contentType = "chart") {
        SectionCard(title = "By payment method") {
            DonutWithList(
                totals = snapshot.paymentMethods,
                currency = state.baseCurrency,
                expandedId = null,
                subcategories = emptyMap(),
                onExpand = {},
            )
        }
    }

    // A4 — merchant leaderboard, Top-N (§5.6).
    item(key = "merchants", contentType = "chart") {
        SectionCard(title = "Top merchants") {
            LfHorizontalBarChart(data = snapshot.toMerchantBars(state.baseCurrency))
        }
    }
}

@Composable
private fun AnalyticsSnapshot.toMerchantBars(currency: String): List<LfBarDatum> {
    val accent = LfTheme.colors.accent
    return merchants.take(TOP_MERCHANTS).map { total ->
        LfBarDatum(
            id = total.id,
            label = total.name,
            value = total.amount.minor,
            formattedValue = MoneyFormat.symbolised(total.amount.minor, currency),
            color = accent,
        )
    }
}

@Composable
private fun RangeRow(selected: AnalyticsRange, onEvent: (AnalyticsEvent) -> Unit) {
    // A horizontally scrolling row rather than a wrapping one: seven ranges in
    // chronological order are a scale, and a scale that wraps mid-way reads as
    // two groups.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        contentPadding = PaddingValues(
            horizontal = LfTheme.spacing.lg,
        ),
    ) {
        items(AnalyticsRange.entries, key = { it.name }) { range ->
            LfChip(
                label = range.label,
                style = if (range == selected) LfChipStyle.Selected else LfChipStyle.Assist,
                onClick = { onEvent(AnalyticsEvent.RangeSelected(range)) },
            )
        }
    }
}

@Composable
private fun TotalCard(snapshot: AnalyticsSnapshot, currency: String) {
    SectionCard(title = null) {
        Text(
            text = MoneyFormat.symbolised(snapshot.total.minor, currency),
            style = LfTheme.typography.amountL,
            color = LfTheme.colors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            // The count comes from `COUNT(DISTINCT id)`, never from summing
            // `txn_count` across categories -- a split bill would count twice.
            text = "${snapshot.transactionCount} transactions",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    LfCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LfTheme.spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            if (title != null) {
                Text(
                    text = title,
                    style = LfTheme.typography.titleM,
                    color = LfTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

/**
 * A2 and A5: a small donut over the ranked list that carries the figures.
 *
 * Only the top slices get an arc; the rest are folded into "Other" so the ring
 * stays legible (§5.6's Top-N + "Other"). The *list* still shows everything,
 * because the ring is orientation and the list is the answer.
 */
@Composable
private fun DonutWithList(
    totals: List<DimensionTotal>,
    currency: String,
    expandedId: String?,
    subcategories: Map<String, List<DimensionTotal>>,
    onExpand: (String) -> Unit,
) {
    if (totals.isEmpty()) return
    val visible = totals.take(TOP_SLICES)
    val otherTotal = totals.drop(TOP_SLICES).sumOf { it.amount.minor }

    val slices = buildList {
        visible.forEach { total ->
            add(
                LfDonutSlice(
                    id = total.id,
                    label = total.name,
                    value = total.amount.minor,
                    color = LfCategoryPalette.colorForId(total.id, total.colorArgb),
                ),
            )
        }
        if (otherTotal > 0L) {
            add(
                LfDonutSlice(
                    id = "other",
                    label = "Other",
                    value = otherTotal,
                    color = LfTheme.colors.textTertiary,
                ),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LfDonutChart(slices = slices)
    }

    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
        totals.forEach { total ->
            DimensionRow(
                total = total,
                currency = currency,
                color = LfCategoryPalette.colorForId(total.id, total.colorArgb),
                onClick = { onExpand(total.id) },
            )
            if (total.id == expandedId) {
                subcategories[total.id].orEmpty().forEach { child ->
                    DimensionRow(
                        total = child,
                        currency = currency,
                        color = LfCategoryPalette.colorForId(child.id, child.colorArgb),
                        onClick = {},
                        indented = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DimensionRow(
    total: DimensionTotal,
    currency: String,
    color: Color,
    onClick: () -> Unit,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) LfTheme.spacing.lg else 0.dp,
                top = LfTheme.spacing.xs,
                bottom = LfTheme.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        LfCategoryDot(name = total.name, colorArgb = color.toArgb())
        Text(
            text = total.name,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DeltaAndAmount(total = total, currency = currency)
    }
}

@Composable
private fun DeltaAndAmount(total: DimensionTotal, currency: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = MoneyFormat.symbolised(total.amount.minor, currency),
            style = LfTheme.typography.amountM,
            color = LfTheme.colors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
        val previous = total.previousAmount
        if (previous != null) {
            val delta = total.amount.minor - previous.minor
            Text(
                // A sign and a figure rather than a percentage: a category that
                // was zero last period has no percentage change, and rendering
                // that as an infinity or a dash is worse than showing what
                // actually moved.
                text = (if (delta >= 0) "+" else "-") +
                    MoneyFormat.symbolised(kotlin.math.abs(delta), currency),
                style = LfTheme.typography.label,
                color = LfTheme.colors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The snapshot as stacked columns.
 *
 * `@Composable` because each segment's colour comes from [LfCategoryPalette],
 * which reads the theme to know whether it is normalising for a dark or a light
 * surface. Computing colours outside composition would mean picking one theme
 * and being wrong in the other.
 *
 * The label is the bucket's start date, not its ordinal. "12 Aug" tells the
 * reader where they are; "7" tells them nothing, and an axis whose labels carry
 * no information is worse than an axis with none, because it looks like it
 * should mean something.
 */
@Composable
private fun AnalyticsSnapshot.toColumns(): List<LfBarColumn> = timeBuckets.map { bucket ->
    LfBarColumn(
        id = bucket.bucket.toString(),
        label = shortDateLabel(bucket.startDate),
        segments = bucket.byCategory.map { category ->
            LfBarSegment(
                id = category.id,
                label = category.name,
                value = category.amount.minor,
                color = LfCategoryPalette.colorForId(category.id, category.colorArgb),
            )
        },
    )
}

/** `12 Aug` from a days-since-epoch value. */
private fun shortDateLabel(epochDay: Int): String {
    val date = java.time.LocalDate.ofEpochDay(epochDay.toLong())
    return "${date.dayOfMonth} " +
        date.month.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        )
}

private const val TOP_SLICES = 6
private const val TOP_MERCHANTS = 8

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun AnalyticsPreview() {
    LfTheme { AnalyticsScreen(state = AnalyticsUiState(isLoading = false), onEvent = {}) }
}
