package com.ledgerflow.feature.dashboard

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth

/**
 * Home's state (SPEC.md §9.3, §5.2).
 *
 * **The screen's first real content, and deliberately only this.** The Dashboard
 * was a parameterless placeholder because its intended content — recent
 * entries, quick stats, budget rings — reads from `daily_rollup`, which arrives
 * with the rollup worker at P3. §5.2's health banner does not wait for that, and
 * giving the screen a state for one banner is the smaller of the two available
 * mistakes: the alternative was a database call inside a composable, which the
 * placeholder's own KDoc ruled out in advance.
 *
 * When P3 lands, this class grows fields. It does not grow a second ViewModel.
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
) {

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
}
