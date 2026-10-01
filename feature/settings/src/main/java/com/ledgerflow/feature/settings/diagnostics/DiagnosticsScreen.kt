package com.ledgerflow.feature.settings.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.chart.LfBarDatum
import com.ledgerflow.core.designsystem.chart.LfHorizontalBarChart
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.diagnostics.CaptureOutcomes
import com.ledgerflow.core.domain.diagnostics.DiagnosticsWindow
import com.ledgerflow.core.domain.diagnostics.DuplicateEvidence
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.KeptCopy
import com.ledgerflow.core.domain.diagnostics.PipelineSpeed
import com.ledgerflow.core.domain.diagnostics.ReadQualityReport
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import com.ledgerflow.core.domain.diagnostics.SenderReadRate
import com.ledgerflow.core.domain.diagnostics.SourceLatency
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.model.EntrySource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Ingest diagnostics (SPEC.md §13 P5; DATAVIZ-PLAN C3–C5): what automatic
 * capture did with the messages it saw.
 *
 * Stateless: state in, events out (CLAUDE.md §5).
 *
 * **Counts and durations, never money and never message text.** This screen
 * can be shown to someone helping debug a parser without showing them a single
 * payment. That is also why it is safe to have no confirmation or lock of its
 * own.
 *
 * **One card shape for every section** (the compactness brief). Messages and
 * Housekeeping always show — "nothing arrived" and "never re-checked" are
 * themselves findings; the other three are hidden when they have nothing to say.
 *
 * The back action sits on its own band above the title (BUG17): "Diagnostics"
 * is one long word, and beside a button its width would be whatever the button
 * left over.
 */
@Composable
public fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onEvent: (DiagnosticsEvent) -> Unit,
    onOpenSuppressed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        ) {
            LfActionRow(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
                alignment = LfActionAlignment.Start,
            ) {
                LfButton(text = "Back", onClick = onBack, style = LfButtonStyle.Inline)
            }
            LfScreenTitle(
                title = "Diagnostics",
                subtitle = "What automatic capture did with the messages it saw. " +
                    "Counts only — no message text, no amounts.",
            )
            Column(
                modifier = Modifier.padding(
                    start = LfTheme.spacing.lg,
                    end = LfTheme.spacing.lg,
                    bottom = LfTheme.spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                LfSegmentedControl(
                    options = WINDOWS.map(::windowLabel),
                    selectedIndex = WINDOWS.indexOf(state.window),
                    onSelect = { index -> onEvent(DiagnosticsEvent.WindowSelected(WINDOWS[index])) },
                )
                when (val report = state.report) {
                    DiagnosticsReport.Loading -> Unit
                    DiagnosticsReport.Unavailable -> DiagnosticsCard(heading = "Nothing to count") {
                        BodyText("Your data could not be opened to count it. Close the app and open it again.")
                    }
                    is DiagnosticsReport.Ready -> ReportCards(report.diagnostics, onOpenSuppressed)
                }
            }
        }
    }
}

private val WINDOWS: List<DiagnosticsWindow> = DiagnosticsWindow.entries

internal fun windowLabel(window: DiagnosticsWindow): String = when (window) {
    DiagnosticsWindow.LAST_30_DAYS -> "30 days"
    DiagnosticsWindow.LAST_90_DAYS -> "90 days"
    DiagnosticsWindow.ALL_TIME -> "All time"
}

@Composable
private fun ReportCards(report: IngestDiagnostics, onOpenSuppressed: () -> Unit) {
    CapturedCard(report.captured)
    if (report.duplicates.total > 0) DuplicatesCard(report.duplicates, onOpenSuppressed)
    if (report.reading.total > 0) ReadingCard(report.reading, readingBasis(report.captured, report.reading))
    if (hasSpeed(report.speed)) SpeedCard(report.speed)
    RollupCard(report.rollup)
}

// ── Messages ────────────────────────────────────────────────────────────────

@Composable
private fun CapturedCard(captured: CaptureOutcomes) {
    DiagnosticsCard(heading = "Messages") {
        BodyText(capturedHeadline(captured), primary = true)
        capturedRows(captured).forEach { (label, count) -> CountRow(label, count.toString()) }
        ignoredLine(captured)?.let { BodyText(it) }
        stuckLine(captured)?.let { WarningText(it) }
    }
}

internal fun capturedHeadline(captured: CaptureOutcomes): String = when (captured.fromFinancialSenders) {
    0 -> "Nothing arrived from your banks or payment apps in this period."
    1 -> "1 message from your banks and payment apps."
    else -> "${captured.fromFinancialSenders} messages from your banks and payment apps."
}

/** The non-zero outcomes, in pipeline order. A zero row says nothing worth its height. */
internal fun capturedRows(captured: CaptureOutcomes): List<Pair<String, Int>> = listOf(
    "Read by a rule" to captured.parsed,
    "Not recognised, kept in the Inbox" to captured.unmatched,
    "Same payment, arrived twice" to captured.duplicates,
    "Errors while reading" to captured.failed,
    "Waiting to be read" to captured.waiting,
).filter { it.second > 0 }

/** Personal SMS: a count and nothing else, and no line at all when there were none. */
internal fun ignoredLine(captured: CaptureOutcomes): String? = when (captured.ignored) {
    0 -> null
    1 -> "1 other SMS was checked, found not to be from a bank, and left alone."
    else -> "${captured.ignored} other SMS were checked, found not to be from a bank, and left alone."
}

internal fun stuckLine(captured: CaptureOutcomes): String? = when (captured.stuck) {
    0 -> null
    1 -> "1 message has waited more than 10 minutes to be read. Capture may have stalled."
    else -> "${captured.stuck} messages have waited more than 10 minutes to be read. " +
        "Capture may have stalled."
}

// ── Duplicates (C3) ─────────────────────────────────────────────────────────

@Composable
private fun DuplicatesCard(duplicates: DuplicateEvidence, onOpenSuppressed: () -> Unit) {
    DiagnosticsCard(heading = "Duplicates caught") {
        BodyText(duplicatesHeadline(duplicates), primary = true)
        duplicates.kept.forEach { kept -> CountRow(keptLabel(kept), kept.count.toString()) }
        // Only when there is something to see there: the Inbox's Suppressed
        // chip is hidden at zero, and a link onto an empty filter is a dead end.
        if (duplicates.stillInInbox > 0) {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Show in Inbox",
                    onClick = onOpenSuppressed,
                    style = LfButtonStyle.Inline,
                )
            }
        }
    }
}

internal fun duplicatesHeadline(duplicates: DuplicateEvidence): String =
    if (duplicates.total == 1) {
        "1 payment arrived twice and was kept once."
    } else {
        "${duplicates.total} payments arrived twice and were kept once."
    }

internal fun keptLabel(kept: KeptCopy): String = when (kept.source) {
    EntrySource.SMS -> "Kept the bank SMS"
    EntrySource.NOTIFICATION -> "Kept the app notification"
    EntrySource.OCR -> "Kept the scanned receipt"
    EntrySource.MANUAL -> "Kept the typed entry"
    EntrySource.IMPORT -> "Kept the imported entry"
    null -> "Kept copy no longer in the Inbox"
}

// ── Reading (C4) ────────────────────────────────────────────────────────────

@Composable
private fun ReadingCard(reading: ReadQualityReport, basis: String?) {
    DiagnosticsCard(heading = "How well messages are read") {
        LfHorizontalBarChart(data = readingBars(reading, LfTheme.colors.accent))
        BodyText(
            "How sure the matching rule was: high is 0.8 or more, low is under 0.5, " +
                "and not recognised means no rule matched.",
        )
        basis?.let { BodyText(it) }
        if (reading.weakestSenders.isNotEmpty()) {
            Text(
                text = "Read worst",
                style = LfTheme.typography.label,
                color = LfTheme.colors.textSecondary,
                modifier = Modifier.semantics { heading() },
            )
            reading.weakestSenders.forEach { sender ->
                CountRow(sender.sender, weakShare(sender))
            }
        }
    }
}

internal fun readingBars(
    reading: ReadQualityReport,
    color: Color,
): List<LfBarDatum> = listOf(
    Triple("high", "High", reading.high),
    Triple("medium", "Medium", reading.medium),
    Triple("low", "Low", reading.low),
    Triple("none", "Not recognised", reading.notRecognised),
).map { (id, label, count) ->
    LfBarDatum(id = id, label = label, value = count.toLong(), formattedValue = count.toString(), color = color)
}

/**
 * Why the bars can add up to less than the Messages card.
 *
 * Every read, unrecognised or duplicate message produced exactly one Inbox
 * candidate, and confidence lives on the candidate. Erasing a candidate from
 * the Inbox keeps the message's record (so Messages still counts it) but takes
 * its confidence with it. Found on the owner's phone on the first day: 52
 * messages, 42 scored, and nothing on screen saying why. Null when every
 * message is still scored.
 */
internal fun readingBasis(captured: CaptureOutcomes, reading: ReadQualityReport): String? {
    val producedCandidates = captured.parsed + captured.unmatched + captured.duplicates
    val erased = producedCandidates - reading.total
    if (erased <= 0) return null
    return if (erased == 1) {
        "Out of $producedCandidates messages: 1 was erased from the Inbox, so it has no score."
    } else {
        "Out of $producedCandidates messages: $erased were erased from the Inbox, so they have no score."
    }
}

/** "4 of 9 weak": the count that ranks the row, then its context. */
internal fun weakShare(sender: SenderReadRate): String = "${sender.weak} of ${sender.total} weak"

// ── Speed (C5) ──────────────────────────────────────────────────────────────

internal fun hasSpeed(speed: PipelineSpeed): Boolean =
    speed.toInbox.isNotEmpty() || speed.decisionMedianMillis != null || speed.oldestWaitingMillis != null

@Composable
private fun SpeedCard(speed: PipelineSpeed) {
    DiagnosticsCard(heading = "Speed") {
        speed.toInbox.forEach { latency ->
            StackedRow(latencyLabel(latency.source), latencyValue(latency))
        }
        speed.decisionMedianMillis?.let { median ->
            StackedRow("Inbox to your decision", decisionValue(median, speed.decisions))
        }
        speed.oldestWaitingMillis?.let { waited ->
            StackedRow("Oldest still waiting", "${formatDuration(waited)} in the Inbox")
        }
    }
}

internal fun latencyLabel(source: IngestSourceType): String = when (source) {
    IngestSourceType.SMS -> "Bank SMS to Inbox"
    IngestSourceType.NOTIFICATION -> "App notification to Inbox"
}

internal fun latencyValue(latency: SourceLatency): String =
    "Typically ${formatDuration(latency.medianMillis)}; " +
        "the slowest tenth took ${formatDuration(latency.p90Millis)} or more."

internal fun decisionValue(medianMillis: Long, decisions: Int): String =
    "Typically ${formatDuration(medianMillis)}, over $decisions " +
        if (decisions == 1) "decision." else "decisions."

/**
 * A duration a person reads: tenths of a second under a minute (pipeline
 * times live there), then whole minutes, hours and days.
 *
 * Integer arithmetic throughout, so a time never shows as "1.2000000000000002 s".
 */
internal fun formatDuration(millis: Long): String {
    val ms = millis.coerceAtLeast(0L)
    return when {
        // Rounding to tenths can reach a whole minute (59.96 s); that is "1 min", not "60.0 s".
        ms < MINUTE - TENTH / 2 -> {
            val tenths = (ms + TENTH / 2) / TENTH
            "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND} s"
        }
        ms < MINUTE -> "1 min"
        ms < HOUR -> "${ms / MINUTE} min"
        ms < 2 * DAY -> "${ms / HOUR} h"
        else -> "${ms / DAY} days"
    }
}

private const val TENTH = 100L
private const val TENTHS_PER_SECOND = 10L
private const val MINUTE = 60_000L
private const val HOUR = 60L * MINUTE
private const val DAY = 24L * HOUR

// ── Housekeeping ────────────────────────────────────────────────────────────

@Composable
private fun RollupCard(rollup: RollupHealth) {
    DiagnosticsCard(heading = "Housekeeping") {
        BodyText(rollupChecked(rollup))
        rollupRepairs(rollup)?.let { (text, isProblem) ->
            if (isProblem) WarningText(text) else BodyText(text)
        }
    }
}

internal fun rollupChecked(rollup: RollupHealth, locale: Locale = Locale.getDefault()): String {
    val at = rollup.lastReconciledAt ?: return "Analytics totals have not been re-checked yet."
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(at))
    return "Analytics totals last re-checked $date."
}

/**
 * The repair count, and whether it is a problem.
 *
 * ADR-0006: a non-zero count on a healthy install means the incremental path
 * drifted and the nightly pass hid it — a bug report, stated as one.
 */
internal fun rollupRepairs(rollup: RollupHealth): Pair<String, Boolean>? {
    rollup.lastReconciledAt ?: return null
    return when (val repaired = rollup.bucketsRepaired ?: 0) {
        0 -> "Nothing needed correcting." to false
        1 -> "The check corrected 1 daily total. It should correct none — worth reporting." to true
        else -> "The check corrected $repaired daily totals. It should correct none — " +
            "worth reporting." to true
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────

/** The screen's one card shape: heading, then content, hairline border via [LfCard]. */
@Composable
private fun DiagnosticsCard(heading: String, content: @Composable () -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = heading,
                style = LfTheme.typography.titleM,
                color = LfTheme.colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun BodyText(text: String, primary: Boolean = false) {
    Text(
        text = text,
        style = LfTheme.typography.bodyM,
        color = if (primary) LfTheme.colors.textPrimary else LfTheme.colors.textSecondary,
    )
}

/** In words and in the warning colour — never colour alone (§9.6). */
@Composable
private fun WarningText(text: String) {
    Text(text = text, style = LfTheme.typography.bodyM, color = LfTheme.colors.warn)
}

/** Label and a short figure on one line; the label wraps, the figure never does. */
@Composable
private fun CountRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(end = LfTheme.spacing.sm),
        )
        Text(
            text = value,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Label over a sentence, for values too long to share a line at font scale 2.0. */
@Composable
private fun StackedRow(label: String, value: String) {
    Column {
        Text(text = label, style = LfTheme.typography.bodyM, color = LfTheme.colors.textSecondary)
        Text(text = value, style = LfTheme.typography.bodyM, color = LfTheme.colors.textPrimary)
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

internal val PreviewDiagnostics: IngestDiagnostics = IngestDiagnostics(
    captured = CaptureOutcomes(
        parsed = 41,
        unmatched = 6,
        duplicates = 9,
        failed = 0,
        waiting = 0,
        ignored = 112,
        stuck = 0,
    ),
    duplicates = DuplicateEvidence(
        kept = listOf(
            KeptCopy(EntrySource.SMS, 7),
            KeptCopy(EntrySource.NOTIFICATION, 1),
            KeptCopy(null, 1),
        ),
    ),
    reading = ReadQualityReport(
        notRecognised = 6,
        low = 3,
        medium = 11,
        high = 36,
        weakestSenders = listOf(
            SenderReadRate("HDFCBK", weak = 5, total = 22),
            SenderReadRate("Google Pay", weak = 3, total = 14),
            SenderReadRate("SBIUPI", weak = 1, total = 9),
        ),
    ),
    speed = PipelineSpeed(
        toInbox = listOf(
            SourceLatency(IngestSourceType.SMS, count = 30, medianMillis = 1_200L, p90Millis = 3_400L),
            SourceLatency(IngestSourceType.NOTIFICATION, count = 26, medianMillis = 800L, p90Millis = 2_100L),
        ),
        decisions = 44,
        decisionMedianMillis = 4L * HOUR,
        oldestWaitingMillis = null,
    ),
    rollup = RollupHealth(lastReconciledAt = 1_790_000_000_000L, bucketsRepaired = 0),
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun DiagnosticsPreview() {
    LfTheme {
        DiagnosticsScreen(
            state = DiagnosticsUiState(report = DiagnosticsReport.Ready(PreviewDiagnostics)),
            onEvent = {},
            onOpenSuppressed = {},
            onBack = {},
        )
    }
}

/** A fresh install: nothing captured, nothing checked — the first card alone. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun DiagnosticsEmptyPreview() {
    LfTheme {
        DiagnosticsScreen(
            state = DiagnosticsUiState(
                report = DiagnosticsReport.Ready(
                    IngestDiagnostics(
                        captured = CaptureOutcomes(0, 0, 0, 0, 0, 0, 0),
                        duplicates = DuplicateEvidence(emptyList()),
                        reading = ReadQualityReport(0, 0, 0, 0, emptyList()),
                        speed = PipelineSpeed(emptyList(), 0, null, null),
                        rollup = RollupHealth(null, null),
                    ),
                ),
            ),
            onEvent = {},
            onOpenSuppressed = {},
            onBack = {},
        )
    }
}
