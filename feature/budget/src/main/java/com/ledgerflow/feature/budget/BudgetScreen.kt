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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.chart.LfBudgetBar
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfFeedback
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.BudgetWords
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.pattern.LfConfirmSheet
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // BUG68: "Budget saved", "Budget deleted" and "Could not delete" were set
    // and never drawn. Posted to the window's one snackbar, so "Budget saved"
    // shows as the editor closes; cleared once posted, so the same message
    // twice in a row still shows twice.
    val feedback = LfFeedback.current
    state.message?.let { message ->
        LaunchedEffect(message) {
            feedback.post(message)
            onEvent(BudgetEvent.MessageShown)
        }
    }

    state.confirmingDelete?.let { DeleteBudgetSheet(it, onEvent) }

    // BUG70: a full-screen destination with no way out but the gesture. The
    // detail template, with "Done" in the band above the title, at the end
    // (BUG17's placement), as Organise, Export and Deleted entries have it.
    LfDetailPage(
        title = "Budgets",
        modifier = modifier,
        // The back arrow beside the title (step F5, owner 2026-10-30), as on
        // every page reached from Profile.
        onBack = onBack,
        notices = {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Add budget",
                    onClick = { onEvent(BudgetEvent.AddClicked) },
                    style = LfButtonStyle.Outlined,
                    enabled = state.canAddBudget,
                )
            }
        },
        statusContent = if (state.showEmptyState) {
            {
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
        } else {
            null
        },
    ) {
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
    LfCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
            LfBudgetBar(
                label = progress.categoryName,
                formattedSpent = MoneyFormat.symbolised(progress.spent.minor, currency),
                formattedBudget = MoneyFormat.symbolised(
                    progress.budget.amount.minor,
                    currency,
                ),
                fraction = progress.fraction,
                projectedFraction = progress.projectedFraction,
                // What is left, as Home says it (step F5, owner 2026-10-30).
                amounts = BudgetWords.headroom(progress.spent, progress.budget.amount, currency),
                // A budget is a plan, not money moving: the primary action's tone, amber
                // once over (step F6, owner 2026-10-30), never its category's colour.
            )

            Text(
                text = periodSummary(progress),
                style = LfTheme.type.label,
                color = if (progress.onCourseToOverrun) {
                    LfTheme.colors.status.warning
                } else {
                    LfTheme.colors.text.muted
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
 * BUG69: Delete was one tap. A sheet, not a `Warning` dialog (owner,
 * 2026-10-27): no spending is touched and the budget can be added again, so
 * the step is consequential rather than irreversible (§5.2).
 */
@Composable
private fun DeleteBudgetSheet(progress: BudgetProgress, onEvent: (BudgetEvent) -> Unit) {
    LfConfirmSheet(
        title = "Delete the ${progress.categoryName} budget?",
        body = "Your spending isn't affected. Only this limit and its alerts go.",
        confirmLabel = "Delete",
        onConfirm = { onEvent(BudgetEvent.DeleteConfirmed) },
        onDismiss = { onEvent(BudgetEvent.DeleteCancelled) },
    )
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

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun BudgetPreview() {
    LfTheme { LfScaffold { BudgetScreen(state = BudgetUiState(isLoading = false), onEvent = {}, onBack = {}) } }
}
