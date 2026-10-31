package com.ledgerflow.feature.dashboard

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.analytics.HomeSummary
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.model.LedgerListItem

/**
 * Home's state (SPEC.md §9.3, §5.2; V2-PLAN BUG-C).
 *
 * The banners came first (§5.2), and Home v1's figures joined them in one
 * class, as this KDoc promised at P1: it grew fields, not a second ViewModel.
 *
 * **Null means "not read yet", never "nothing there".** Every Home v1 field
 * starts null, so the first frame shows neither an empty state nor zeros to a
 * user whose vault is full; the empty state needs both books to have answered
 * empty ([showsEmptyState]).
 */
@Immutable
public data class DashboardUiState(

    /**
     * Whether capture is working, per §5.2.
     *
     * Starts at [NotificationCaptureHealth.RECONNECTING] rather than at a
     * "loading" sentinel, because that value is *already* the honest "nothing to
     * report yet" — it renders no banner, which is exactly what an unpolled
     * screen should show. A separate loading state would add a branch whose only
     * behaviour is identical to this one.
     */
    val captureHealth: NotificationCaptureHealth = NotificationCaptureHealth.RECONNECTING,

    /**
     * Whether to ask for a backup (BUG4(c) as amended; owner, 2026-09-19).
     * Null means no reminder — including before the backup date has been read,
     * so a first frame never flashes "No backup yet" at someone who has one.
     */
    val backupReminder: BackupReminder? = null,

    /**
     * The status line's backup half (step F5): null until the backup date has
     * been read, then how long ago the last verified backup was.
     */
    val lastBackup: LastBackup? = null,

    /**
     * When background capture could not reach the vault (§16 Q16), until the
     * user closes it. Null means nothing was lost, or the user has seen it.
     */
    val captureGap: CaptureGap? = null,

    /**
     * Crash reports kept on the phone since the app last stopped unexpectedly
     * (BUG7(c)), until the user saves or dismisses them.
     */
    val crashReports: CrashSummary? = null,

    /** The last "Save report" could not write; the reports are still there. */
    val crashSaveFailed: Boolean = false,

    /** Inbox items waiting for a tap (Law 1). */
    val pendingCount: Int = 0,

    /** The month's figures; null until the first read lands. */
    val summary: HomeSummary? = null,

    /** `app_meta.baseCurrency`; every Home amount is in it (§1.3). */
    val baseCurrency: String = DEFAULT_CURRENCY,

    /** The debit book's newest entries; null until read. */
    val recentSpending: List<LedgerListItem>? = null,

    /** The credit book's newest entries, a separate list (Law 2); null until read. */
    val recentIncome: List<LedgerListItem>? = null,

    /**
     * Settings > Security & privacy > Hide amounts on Home (V2-PLAN §3.5).
     * Null until the setting has been read: the figures come from the vault
     * and the setting from its own file, and either can land first.
     */
    val hideAmounts: Boolean? = null,

    /**
     * The user tapped a hidden amount. Never saved: leaving Home or the app
     * hides them again, so nothing stays shown under the app lock or in
     * Recents.
     */
    val amountsRevealed: Boolean = false,
) {

    /**
     * Every amount on Home is drawn as a mask (`LfMaskedAmount`) until one is
     * tapped -- **and until the setting has been read**. Shown by default, a
     * figure that arrived first would be on screen for a frame for someone
     * who asked for it to be hidden; masked by default, someone who did not
     * sees dots for that frame, which is the cheaper mistake.
     */
    public val amountsHidden: Boolean get() = hideAmounts != false && !amountsRevealed


    /**
     * "Nothing here yet", only when **both** books have answered and neither
     * holds a live entry (BUG54). Home drew it unconditionally until v2.
     *
     * The recent lists carry no window, so an empty one is an empty book: no
     * separate "has entries" read is needed to decide this.
     */
    public val showsEmptyState: Boolean
        get() = recentSpending?.isEmpty() == true && recentIncome?.isEmpty() == true

    /** The month card and everything under it: whenever the vault has anything. */
    public val showsSummary: Boolean
        get() = recentSpending != null && recentIncome != null && !showsEmptyState

    public val showsReview: Boolean get() = pendingCount > 0

    public val showsBudgets: Boolean get() = summary?.budgets?.isNotEmpty() == true

    public val showsRunway: Boolean get() = summary?.runway?.isNotEmpty() == true

    /** Hidden when the month has no spending: "0% arrived by itself" says nothing. */
    public val showsCoverage: Boolean get() = summary?.captureCoverage?.isEmpty == false

    public val showsRecentSpending: Boolean get() = recentSpending?.isNotEmpty() == true

    public val showsRecentIncome: Boolean get() = recentIncome?.isNotEmpty() == true


    /**
     * Whether the banner is on screen at all.
     *
     * Two of the five states show it and three do not, and the three silent ones
     * are silent for different reasons: connected is fine, reconnecting is not
     * yet worth saying, and unavailable is not actionable. Naming the positive
     * set here keeps the screen from re-deriving that judgement.
     */
    public val showsCaptureBanner: Boolean
        get() = captureHealth == NotificationCaptureHealth.NOT_GRANTED ||
            captureHealth == NotificationCaptureHealth.DEAD

    public companion object {
        /** Matches `EntryUiState.DEFAULT_CURRENCY`; onboarding always sets one. */
        public const val DEFAULT_CURRENCY: String = "INR"
    }
}

/** How long ago the last verified backup was, for the status line (step F5). */
public sealed interface LastBackup {
    public data object Never : LastBackup

    /** Whole days; 0 is today. */
    public data class DaysAgo(val days: Long) : LastBackup
}
