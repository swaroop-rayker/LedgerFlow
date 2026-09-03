package com.ledgerflow.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.chart.LfBudgetBar
import com.ledgerflow.core.designsystem.chart.LfCategoryPalette
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.BudgetProgress

/**
 * Budget management (`SPEC.md` §5.7).
 *
 * **One card shape, actions inline** — `CLAUDE.md`'s brief: a budget row is one
 * thing plus its two actions, so it costs about two lines rather than a card
 * with a header, a divider and a row of pills. The actions are
 * `LfButtonStyle.Inline` inside an `LfActionRow`, which is what makes them fit
 * on one line and wrap as whole controls when they cannot.
 *
 * **The bar is the content here, unlike on Analytics.** On the Analytics tab a
 * budget is one of nine things; this screen exists to answer "how am I doing
 * against my budgets", so the bar and its two figures are the row.
 */
@Composable
public fun BudgetScreen(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = PaddingValues(bottom = LfTheme.spacing.xl),
    ) {
        item(key = "title", contentType = "title") {
            LfScreenTitle(title = "Budgets")
        }

        item(key = "add", contentType = "add") {
            LfActionRow(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                alignment = LfActionAlignment.Start,
            ) {
                LfButton(
                    text = "Add budget",
                    onClick = { onEvent(BudgetEvent.AddClicked) },
                    style = LfButtonStyle.Outlined,
                    enabled = state.canAddBudget,
                )
            }
        }

        if (state.showEmptyState) {
            item(key = "empty", contentType = "empty") {
                LfEmptyState(
                    title = "No budgets yet",
                    body = if (state.canAddBudget) {
                        "Set a monthly limit on a category and this screen will " +
                            "track how much of it is left."
                    } else {
                        // The button is disabled and the reason is not obvious,
                        // so the empty state says it rather than leaving a dead
                        // control to be puzzled over.
                        "Add a category first — a budget needs something to limit."
                    },
                )
            }
            return@LazyColumn
        }

        items(state.budgets, key = { it.budget.id }, contentType = { "budget" }) { progress ->
            BudgetCard(
                progress = progress,
                currency = state.baseCurrency,
                onEdit = { onEvent(BudgetEvent.EditClicked(progress.budget.id)) },
                onDelete = { onEvent(BudgetEvent.DeleteClicked(progress.budget.id)) },
            )
        }
    }
}

@Composable
private fun BudgetCard(
    progress: BudgetProgress,
    currency: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    LfCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LfTheme.spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            LfBudgetBar(
                label = progress.categoryName,
                formattedSpent = MoneyFormat.symbolised(progress.spent.minor, currency),
                formattedBudget = MoneyFormat.symbolised(
                    progress.budget.amount.minor,
                    currency,
                ),
                fraction = progress.fraction,
                projectedFraction = progress.projectedFraction(),
                color = LfCategoryPalette.colorForId(
                    progress.budget.categoryId,
                    progress.categoryColorArgb,
                ),
            )

            Text(
                text = periodSummary(progress),
                style = LfTheme.typography.label,
                color = if (progress.onCourseToOverrun) {
                    LfTheme.colors.warn
                } else {
                    LfTheme.colors.textTertiary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = "Edit",
                    onClick = onEdit,
                    style = LfButtonStyle.Inline,
                )
                LfButton(
                    text = "Delete",
                    onClick = onDelete,
                    style = LfButtonStyle.Inline,
                )
            }
        }
    }
}

/**
 * "Day 12 of 30" plus the projection when it overruns.
 *
 * The day count is what makes the bar readable: 60% spent is fine on day 20 and
 * alarming on day 3, and the bar alone cannot say which.
 */
@Composable
private fun periodSummary(progress: BudgetProgress): String {
    val length = progress.periodEnd - progress.periodStart + 1
    val base = "Day ${progress.daysElapsed} of $length"
    return if (progress.onCourseToOverrun) "$base · on course to overrun" else base
}

/** Today's pace as a fraction of the budget — a chart coordinate, not money. */
private fun BudgetProgress.projectedFraction(): Float =
    if (budget.amount.minor <= 0L) {
        0f
    } else {
        (projectedSpend.minor.toDouble() / budget.amount.minor.toDouble()).toFloat()
    }

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun BudgetPreview() {
    LfTheme { BudgetScreen(state = BudgetUiState(isLoading = false), onEvent = {}) }
}
