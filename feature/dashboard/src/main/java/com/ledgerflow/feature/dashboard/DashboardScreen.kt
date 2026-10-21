package com.ledgerflow.feature.dashboard

import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.designsystem.chart.LfBudgetBar
import com.ledgerflow.core.designsystem.chart.LfCategoryPalette
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.BookMonth
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.CaptureCoverage
import com.ledgerflow.core.domain.analytics.HomeSummary
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.ListenerHealth
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.entry.LfEntryRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Home, wired (SPEC.md §5.2).
 *
 * The resume poll lives here rather than in the ViewModel's `init` alone,
 * because the interesting transition is the *return*: the banner's action sends
 * the user to a system Settings page in another task, and the resume that
 * follows is the only moment the app can learn the grant changed. Without it
 * the user would grant access, come back, and still be looking at a banner
 * telling them to grant access.
 */
@Composable
public fun DashboardRoute(
    onSetUpNotifications: () -> Unit,
    onBackUpNow: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenInbox: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenLedger: (LedgerType) -> Unit = {},
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // BUG7(c): Android's own picker chooses where the report goes; nothing is
    // written anywhere the user did not pick, and nothing is sent (Law 6).
    val saveReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.saveCrashReports(it.toString()) } }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // "Fully drawn" is Home with its figures, not the first frame of banners
    // (docs/PERF-120HZ-PLAN.md §6), so startup is measured to a real Home.
    // The summary arrives even for an empty or unopenable vault (an empty
    // snapshot), so this always fires.
    ReportDrawnWhen { state.summary != null }

    DashboardScreen(
        state = state,
        onSetUpNotifications = onSetUpNotifications,
        onBackUpNow = onBackUpNow,
        onAcknowledgeCaptureGap = viewModel::acknowledgeCaptureGap,
        onSaveCrashReports = { saveReport.launch(viewModel.crashReportFileName()) },
        onDismissCrashReports = viewModel::dismissCrashReports,
        onOpenInbox = onOpenInbox,
        onOpenBudgets = onOpenBudgets,
        onOpenAnalytics = onOpenAnalytics,
        onOpenLedger = onOpenLedger,
        modifier = modifier,
    )
}

/**
 * Home (SPEC.md §9.3; V2-PLAN BUG-C).
 *
 * The banners first, then the month: what is waiting for a tap, the two books'
 * figures side by side and never combined (Law 2), the debit-only extras
 * (budgets, what is due, how much arrived by itself), and the newest entries of
 * each book as two separate lists. Each card hides when it has nothing to say,
 * and "Nothing here yet" appears only when both books are empty — until v2 it
 * was drawn whatever the vault held (BUG54).
 *
 * §5.2's health banner sits **above** the content rather than inside it. "No
 * entries yet" is a fact about the ledger, and "capture is not working" is a
 * fact about the pipeline that fills it. Folding the second into the first is
 * precisely the confusion `CLAUDE.md` §7 forbids — a dead listener must not
 * look like an empty Inbox, and by the same argument it must not look like an
 * empty ledger.
 */
@Composable
public fun DashboardScreen(
    state: DashboardUiState,
    onSetUpNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    onBackUpNow: () -> Unit = {},
    onAcknowledgeCaptureGap: () -> Unit = {},
    onSaveCrashReports: () -> Unit = {},
    onDismissCrashReports: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenLedger: (LedgerType) -> Unit = {},
) {
    // A `LazyColumn`, not a scrolling `Column`, for the tab switch: a `Column`
    // composes and measures every card and both recent lists on the frame Home
    // appears, including everything below the fold -- the costliest first frame
    // of the four tabs (28 ms, PERF-120HZ-PLAN §5). One item per card, under the
    // same `spacedBy`, so the layout is the one the `Column` drew.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        item(key = "title", contentType = "title") {
            LfScreenTitle(title = "Home")
        }

        // First: a loss has already happened, where the cards below it are
        // about what to do next.
        state.captureGap?.let { gap ->
            item(key = "capture-gap", contentType = "banner") {
                CaptureGapCard(
                    gap = gap,
                    onAcknowledge = onAcknowledgeCaptureGap,
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                )
            }
        }

        state.crashReports?.let { reports ->
            item(key = "crash-reports", contentType = "banner") {
                CrashReportCard(
                    reports = reports,
                    saveFailed = state.crashSaveFailed,
                    onSave = onSaveCrashReports,
                    onDismiss = onDismissCrashReports,
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                )
            }
        }

        state.backupReminder?.let { reminder ->
            item(key = "backup-reminder", contentType = "banner") {
                BackupReminderCard(
                    reminder = reminder,
                    onBackUpNow = onBackUpNow,
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                )
            }
        }

        if (state.showsCaptureBanner) {
            item(key = "capture-banner", contentType = "banner") {
                CaptureHealthBanner(
                    health = state.captureHealth,
                    onAction = onSetUpNotifications,
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                )
            }
        }

        homeBody(
            state = state,
            onOpenInbox = onOpenInbox,
            onOpenBudgets = onOpenBudgets,
            onOpenAnalytics = onOpenAnalytics,
            onOpenLedger = onOpenLedger,
        )

        // The last card clears the bottom bar's shadow rather than touching it.
        item(key = "end", contentType = "end") {
            Spacer(modifier = Modifier.height(LfTheme.spacing.md))
        }
    }
}

/**
 * Home v1 (V2-PLAN BUG-C), under the banners: what is waiting, the month, and
 * each book's newest entries. Emits straight into the screen's list, so its
 * cards share the banners' spacing.
 */
private fun LazyListScope.homeBody(
    state: DashboardUiState,
    onOpenInbox: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenLedger: (LedgerType) -> Unit,
) {
    // Not inside the summary branch: a first-day user with only pending
    // suggestions has nothing in either book and still has work waiting.
    if (state.showsReview) {
        item(key = "review", contentType = "card") {
            ReviewCard(
                count = state.pendingCount,
                onOpenInbox = onOpenInbox,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }

    if (state.showsEmptyState) {
        item(key = "empty", contentType = "empty") {
            LfEmptyState(
                title = "Nothing here yet",
                body = "Your recent spending, budgets and quick stats appear here once " +
                    "there are entries to summarise.",
            )
        }
    }

    if (state.showsSummary) {
        item(key = "month", contentType = "month") {
            MonthCard(
                summary = state.summary,
                currency = state.baseCurrency,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
        state.summary?.let { summary ->
            spendingCards(
                state = state,
                summary = summary,
                onOpenBudgets = onOpenBudgets,
                onOpenAnalytics = onOpenAnalytics,
            )
        }
    }

    state.recentSpending?.takeIf { state.showsRecentSpending }?.let { rows ->
        item(key = "recent-spending", contentType = "recent") {
            RecentSection(
                title = "Recent spending",
                rows = rows,
                onSeeAll = { onOpenLedger(LedgerType.DEBIT) },
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }
    state.recentIncome?.takeIf { state.showsRecentIncome }?.let { rows ->
        item(key = "recent-income", contentType = "recent") {
            RecentSection(
                title = "Recent income",
                rows = rows,
                onSeeAll = { onOpenLedger(LedgerType.CREDIT) },
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }
}

/** The debit book's extras, each only when it has something to say. */
private fun LazyListScope.spendingCards(
    state: DashboardUiState,
    summary: HomeSummary,
    onOpenBudgets: () -> Unit,
    onOpenAnalytics: () -> Unit,
) {
    if (state.showsBudgets) {
        item(key = "budgets", contentType = "card") {
            BudgetsCard(
                budgets = summary.budgets,
                currency = state.baseCurrency,
                onOpenBudgets = onOpenBudgets,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }
    if (state.showsRunway) {
        item(key = "runway", contentType = "card") {
            RunwayCard(
                summary = summary,
                currency = state.baseCurrency,
                onOpenAnalytics = onOpenAnalytics,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }
    if (state.showsCoverage) {
        item(key = "coverage", contentType = "card") {
            CoverageCard(
                coverage = summary.captureCoverage,
                onOpenAnalytics = onOpenAnalytics,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }
    }
}

/** Inbox items waiting (Law 1: nothing reaches a book without this tap). */
@Composable
private fun ReviewCard(count: Int, onOpenInbox: () -> Unit, modifier: Modifier = Modifier) {
    LfCard(modifier = modifier) {
        LineWithAction(
            action = "Open Inbox",
            onAction = onOpenInbox,
        ) {
            Text(
                text = "To review: $count",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
        }
    }
}

/**
 * The month so far, one line per book, each against the same days of last
 * month (owner, 2026-10-14).
 *
 * **Two figures, labelled in words, and no third.** *Spent* and *Received* are
 * never netted (Law 2), and the colour is a second cue rather than the only one
 * (§9.6). One book per line, the word left and the amount right as on every
 * other row in the app; side by side the two read as a pair that should be
 * compared, and stacked at large text they zig-zagged.
 *
 * Drawn with placeholders before [summary] arrives, at the same height, so
 * nothing below it moves when the figures land.
 */
@Composable
private fun MonthCard(summary: HomeSummary?, currency: String, modifier: Modifier = Modifier) {
    val colors = LfTheme.colors
    val period = summary?.let { periodLabel(it.window.from, it.window.to) }
    val previousPeriod = summary?.let { periodLabel(it.previousWindow.from, it.previousWindow.to) }
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Text(
                text = period?.let { "This month, $it" } ?: "This month",
                style = LfTheme.typography.label,
                color = colors.textSecondary,
            )
            BookFigure(
                label = "Spent",
                book = summary?.spending,
                previousPeriod = previousPeriod,
                currency = currency,
                color = colors.debit,
            )
            BookFigure(
                label = "Received",
                book = summary?.income,
                previousPeriod = previousPeriod,
                currency = currency,
                color = colors.credit,
            )
            Text(
                text = summary?.let { "Spent today: " + MoneyFormat.symbolised(it.spentToday.minor, currency) }
                    ?: PLACEHOLDER_LINE,
                style = LfTheme.typography.label,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * One book's figure, its word, and how it compares; one announcement for all
 * three.
 *
 * `amountM`, the row size, not `amountL`: the amount is `softWrap = false`, and
 * at font scale 2.0 `amountL` would need more than the card's width for a
 * figure like ₹12,34,567.00. Hierarchy comes from the word beside it and the
 * book's colour rather than from size.
 */
@Composable
private fun BookFigure(
    label: String,
    book: BookMonth?,
    previousPeriod: String?,
    currency: String,
    color: Color,
) {
    val comparison = if (book != null && previousPeriod != null) {
        comparisonSentence(book.total.minor, book.previousTotal.minor, currency, previousPeriod)
    } else {
        PLACEHOLDER_LINE
    }
    val spoken = if (book != null && previousPeriod != null) {
        "$label this month, " + MoneyFormat.spoken(book.total.minor, currency) + ". " +
            comparisonSentence(book.total.minor, book.previousTotal.minor, currency, previousPeriod, spoken = true)
    } else {
        "$label this month, loading"
    }
    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        LfAdaptiveRow(
            leading = {
                Text(text = label, style = LfTheme.typography.bodyL, color = LfTheme.colors.textPrimary)
            },
            trailing = {
                Text(
                    text = book?.let { MoneyFormat.symbolised(it.total.minor, currency) } ?: PLACEHOLDER_AMOUNT,
                    style = LfTheme.typography.amountM,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            },
        )
        Text(
            text = comparison,
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
            textAlign = LfTextAlign.end,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The budgets closest to their limit, in the bar Budgets and Analytics draw
 * (the owner chose it over a ring, 2026-10-14). Debit only (§5.7).
 */
@Composable
private fun BudgetsCard(
    budgets: List<BudgetProgress>,
    currency: String,
    onOpenBudgets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            SectionHeading(title = "Budgets", action = "All budgets", onAction = onOpenBudgets)
            budgets.forEach { progress ->
                LfBudgetBar(
                    label = progress.categoryName,
                    formattedSpent = MoneyFormat.symbolised(progress.spent.minor, currency),
                    formattedBudget = MoneyFormat.symbolised(progress.budget.amount.minor, currency),
                    fraction = progress.fraction,
                    projectedFraction = progress.projectedFraction,
                    color = LfCategoryPalette.colorForId(
                        progress.budget.categoryId,
                        progress.categoryColorArgb,
                    ),
                )
            }
        }
    }
}

/**
 * Detected charges due before the month ends (A10's runway, read to the
 * month's end). "Expected", not "due": a pattern, not a schedule.
 */
@Composable
private fun RunwayCard(
    summary: HomeSummary,
    currency: String,
    onOpenAnalytics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = MoneyFormat.symbolised(summary.runwayTotal.minor, currency) +
                    " expected by " + dayLabel(summary.runwayThrough),
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            LineWithAction(action = "Open Analytics", onAction = onOpenAnalytics) {
                Text(
                    text = runwayDetail(summary.runway.map { it.name }),
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** C1 on Home (owner, 2026-09-30): the month's spending by value, count beneath. */
@Composable
private fun CoverageCard(coverage: CaptureCoverage, onOpenAnalytics: () -> Unit, modifier: Modifier = Modifier) {
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "${coverage.automaticPercentByValue}% of this month's spending was captured automatically",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textPrimary,
            )
            LineWithAction(action = "Details", onAction = onOpenAnalytics) {
                Text(
                    text = "${coverage.automatic.count} of ${coverage.totalCount} " +
                        if (coverage.totalCount == 1) "entry" else "entries",
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * One book's newest entries, in the Ledger's own row (`LfEntryRow`).
 *
 * Not wrapped in a card: each row is already the Ledger's bordered row, and a
 * card around bordered rows is two outlines for one list. The heading keeps the
 * cards' inset and type, so the section reads as part of the same screen.
 * Read-only: deleting is the Ledger's.
 */
@Composable
private fun RecentSection(
    title: String,
    rows: List<LedgerListItem>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
        SectionHeading(title = title, action = "See all", onAction = onSeeAll)
        rows.forEach { row -> key(row.id) { LfEntryRow(item = row, withDate = true) } }
    }
}

/**
 * A section's name with its one action on the same line.
 *
 * One action, so it cannot wrap mid-actions (the case CLAUDE.md §5 gives the
 * heading its own line for); [LfAdaptiveRow] drops the whole control below the
 * name when both will not fit, which is the BUG9 way to degrade.
 */
@Composable
private fun SectionHeading(title: String, action: String, onAction: () -> Unit) {
    LineWithAction(action = action, onAction = onAction) {
        Text(text = title, style = LfTheme.typography.label, color = LfTheme.colors.textSecondary)
    }
}

/**
 * A line of content with one `Inline` action at its end, on the same line
 * while both fit and below it, whole, when they do not.
 *
 * A card that is one fact and one action costs one line this way; with the
 * action in a row of its own it cost two and a half (measured on the first
 * Home goldens, 2026-10-16).
 */
@Composable
private fun LineWithAction(action: String, onAction: () -> Unit, content: @Composable () -> Unit) {
    LfAdaptiveRow(
        leading = content,
        trailing = { LfButton(text = action, style = LfButtonStyle.Inline, onClick = onAction) },
    )
}

/**
 * A figure against the same days of last month, in words rather than a
 * percentage: a book that was empty last month has no percentage change, and
 * Analytics shows a change the same way (an amount, not a ratio).
 */
internal fun comparisonSentence(
    current: Long,
    previous: Long,
    currency: String,
    period: String,
    spoken: Boolean = false,
): String {
    val delta = current - previous
    // Spoken, the amount is words ("5,310.00 rupees"): screen readers skip the
    // ₹ glyph often enough that §9.6 asks for it everywhere TalkBack reads one.
    // Found on LF Bench's Home, where the figure was spoken and its comparison
    // was not.
    fun amount(minor: Long) =
        if (spoken) MoneyFormat.spoken(minor, currency) else MoneyFormat.symbolised(minor, currency)
    return when {
        delta > 0 -> amount(delta) + " more than " + period
        delta < 0 -> amount(-delta) + " less than " + period
        else -> "Same as $period"
    }
}

/** `1–16 Oct`, or `1 Nov` for a one-day window. Both ends are in one month. */
internal fun periodLabel(from: Int, to: Int): String {
    val start = LocalDate.ofEpochDay(from.toLong())
    val end = LocalDate.ofEpochDay(to.toLong())
    val month = end.format(monthFormat())
    return if (from == to) "${end.dayOfMonth} $month" else "${start.dayOfMonth}–${end.dayOfMonth} $month"
}

/** `31 Oct`. */
internal fun dayLabel(epochDay: Int): String {
    val date = LocalDate.ofEpochDay(epochDay.toLong())
    return "${date.dayOfMonth} ${date.format(monthFormat())}"
}

/** "1 recurring charge: Rent" / "2 recurring charges: Netflix, Rent". */
internal fun runwayDetail(names: List<String>): String =
    if (names.size == 1) {
        "1 recurring charge: ${names.single()}"
    } else {
        "${names.size} recurring charges: ${names.joinToString(", ")}"
    }

/** A month's short name, in English while the app is (BUG61, `DisplayLocale`). */
private fun monthFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", DisplayLocale.current())

/** Holds a figure's line until the figure arrives, so the card keeps its height. */
private const val PLACEHOLDER_AMOUNT = "—"
private const val PLACEHOLDER_LINE = " "

/**
 * §5.2's health banner.
 *
 * ## Two states, two sentences, and the difference matters
 *
 * [NotificationCaptureHealth.NOT_GRANTED] is a permission the user has not
 * given. [NotificationCaptureHealth.DEAD] is one they *did* give, which the
 * system has since stopped honouring — the OEM battery-killer case §5.2 names.
 * Telling someone to grant a permission they already granted is how a health
 * banner loses its reader, so the second case says what actually happened and
 * points at the setting that causes it.
 *
 * ## Why it is a card and not a coloured strip
 *
 * One shape per screen. Home's other content is `LfCard`-shaped and P3's stats
 * will be too, so a bespoke banner surface would read as a second design the
 * moment anything else lands beside it. The weight comes from the `warn` token
 * on the heading and from position — first thing under the title — rather than
 * from a filled background, which at this size reads as an error the user has
 * caused.
 *
 * Never rendered for the three healthy or unactionable states; [DashboardUiState.showsCaptureBanner]
 * owns that decision so the screen does not re-derive it.
 */
@Composable
private fun CaptureHealthBanner(
    health: NotificationCaptureHealth,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dead = health == NotificationCaptureHealth.DEAD
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = if (dead) {
                    "Notification capture has stopped"
                } else {
                    "Notification capture is off"
                },
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.warn,
            )
            Text(
                text = if (dead) {
                    "Android hasn't reconnected LedgerFlow's listener for over " +
                        "${DEAD_THRESHOLD_HOURS} hours. Battery optimisation is the " +
                        "usual cause. Payments are not reaching your Inbox."
                } else {
                    "Most UPI payments never send an SMS, so they are not reaching " +
                        "your Inbox at all."
                },
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = if (dead) "Check settings" else "Set up",
                    style = LfButtonStyle.Inline,
                    onClick = onAction,
                )
            }
        }
    }
}

/**
 * The backup reminder (BUG4(c) as amended; owner, 2026-09-19).
 *
 * **One line and an action**, in Home's one card shape: a reminder that fills
 * the screen above a ledger is nagging, and one that can be dismissed while it
 * is still true defeats the reason it exists. Above the capture banner, because
 * a phone lost today loses data whether or not capture is working.
 */
@Composable
private fun BackupReminderCard(
    reminder: BackupReminder,
    onBackUpNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = when (reminder) {
                    BackupReminder.NeverBackedUp ->
                        "No backup yet. Your data exists only on this phone."
                    is BackupReminder.Stale ->
                        "Last backup ${reminder.daysAgo} days ago. Anything since is only on this phone."
                },
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.warn,
            )
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = "Back up now",
                    style = LfButtonStyle.Inline,
                    onClick = onBackUpNow,
                )
            }
        }
    }
}

/**
 * §16 Q16's card: background capture could not reach the vault in this window.
 *
 * The window, never the count: a shut vault fails every capture, personal
 * messages included, so a number would overstate missed payments. It tells the
 * user where to look instead. The same card shape and `warn` colour as the
 * backup reminder beside it, and it stays until "Got it" -- a later successful
 * capture does not undo a message that was already lost.
 */
@Composable
private fun CaptureGapCard(
    gap: CaptureGap,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = TimeStamp.of(gap.firstMissedAt, withDate = true)
    val last = TimeStamp.of(gap.lastMissedAt, withDate = true)
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = captureGapSentence(first, last),
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.warn,
            )
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(text = "Got it", style = LfButtonStyle.Inline, onClick = onAcknowledge)
            }
        }
    }
}

/**
 * BUG7(c)'s offer, on the launch after a crash.
 *
 * Says what the report is before asking anything of the user: kept on the
 * phone, nothing personal in it, theirs to save or throw away. `textSecondary`
 * rather than `warn` -- the app is running again, and the cards above it are
 * about things that are still wrong.
 */
@Composable
private fun CrashReportCard(
    reports: CrashSummary,
    saveFailed: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = TimeStamp.of(reports.latestAt, withDate = true)
    LfCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = crashReportSentence(reports.count, latest),
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            if (saveFailed) {
                Text(
                    text = "The report couldn't be saved there. It is still on this phone.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.warn,
                )
            }
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(text = "Dismiss", style = LfButtonStyle.Inline, onClick = onDismiss)
                LfButton(text = "Save report", style = LfButtonStyle.Inline, onClick = onSave)
            }
        }
    }
}

/** The crash card's words: once, or how many times and the latest. */
internal fun crashReportSentence(count: Int, latest: String): String {
    val stopped = if (count == 1) {
        "LedgerFlow stopped unexpectedly on $latest."
    } else {
        "LedgerFlow stopped unexpectedly $count times, most recently on $latest."
    }
    return "$stopped A report with no personal data was kept on this phone. You can save it " +
        "to share with the developer."
}

/** The card's words, from two formatted stamps; one stamp when they are the same. */
internal fun captureGapSentence(first: String, last: String): String {
    val window = if (first == last) "At $first" else "Between $first and $last"
    return "$window, LedgerFlow couldn't record messages in the background. Payments " +
        "from that time may be missing: check your bank or payment app."
}

/**
 * The threshold, in the units the sentence uses.
 *
 * Derived from [ListenerHealth.DEAD_THRESHOLD_MILLIS] rather than written as
 * "6", so the banner cannot end up claiming a number the rule no longer uses.
 * That is not hypothetical caution: the sentence and the constant are edited by
 * different people for different reasons.
 */
private val DEAD_THRESHOLD_HOURS: Long =
    ListenerHealth.DEAD_THRESHOLD_MILLIS / (60L * 60L * 1000L)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun DashboardPreview() {
    LfTheme {
        DashboardScreen(state = DashboardUiState(), onSetUpNotifications = {})
    }
}

@PreviewFontScale
@PreviewLightDark
@Composable
private fun DashboardNotGrantedPreview() {
    LfTheme {
        DashboardScreen(
            state = DashboardUiState(captureHealth = NotificationCaptureHealth.NOT_GRANTED),
            onSetUpNotifications = {},
        )
    }
}

@PreviewFontScale
@PreviewLightDark
@Composable
private fun DashboardBackupReminderPreview() {
    LfTheme {
        DashboardScreen(
            state = DashboardUiState(
                captureHealth = NotificationCaptureHealth.NOT_GRANTED,
                backupReminder = BackupReminder.Stale(daysAgo = 12),
            ),
            onSetUpNotifications = {},
        )
    }
}

@PreviewFontScale
@PreviewLightDark
@Composable
private fun DashboardDeadPreview() {
    LfTheme {
        DashboardScreen(
            state = DashboardUiState(captureHealth = NotificationCaptureHealth.DEAD),
            onSetUpNotifications = {},
        )
    }
}
