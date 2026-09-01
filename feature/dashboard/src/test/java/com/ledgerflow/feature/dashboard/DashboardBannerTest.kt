package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import org.junit.Test

/**
 * Which health states put §5.2's banner on Home, and which stay quiet.
 *
 * The interesting assertion is the *quiet* set. A banner that appears whenever
 * anything is less than perfect is one the user learns to scroll past, and two
 * of the three silent states are silent for reasons that look like bugs until
 * they are written down: [NotificationCaptureHealth.RECONNECTING] is the
 * ordinary first second of every cold start, and
 * [NotificationCaptureHealth.UNAVAILABLE] is a state the user cannot act on.
 *
 * Written over the whole enum rather than over the two positive cases, so
 * adding a sixth state forces a decision here instead of silently defaulting to
 * "no banner".
 */
class DashboardBannerTest {

    private fun showsBannerFor(health: NotificationCaptureHealth) =
        DashboardUiState(captureHealth = health).showsCaptureBanner

    @Test
    fun banner_showsWhenTheGrantIsMissing() {
        assertThat(showsBannerFor(NotificationCaptureHealth.NOT_GRANTED)).isTrue()
    }

    @Test
    fun banner_showsWhenTheListenerHasBeenDeadTooLong() {
        assertThat(showsBannerFor(NotificationCaptureHealth.DEAD)).isTrue()
    }

    @Test
    fun banner_staysQuietWhileConnected() {
        assertThat(showsBannerFor(NotificationCaptureHealth.CONNECTED)).isFalse()
    }

    /**
     * The cold-start state, and the reason the six-hour threshold exists at all.
     *
     * Home is the shell's start destination, so this value is on screen for the
     * first frames of every single launch. Showing a banner for it would mean a
     * warning flashing on every open of a perfectly healthy install.
     */
    @Test
    fun banner_staysQuietWhileReconnecting() {
        assertThat(showsBannerFor(NotificationCaptureHealth.RECONNECTING)).isFalse()
    }

    /** Nothing the user can do, so nothing to say. */
    @Test
    fun banner_staysQuietWhenCaptureIsUnavailable() {
        assertThat(showsBannerFor(NotificationCaptureHealth.UNAVAILABLE)).isFalse()
    }

    /**
     * The default state renders no banner.
     *
     * Home is constructed before anything is polled, and the first composition
     * happens well before the first answer arrives. A default that showed the
     * banner would put a warning on screen for every launch and then take it
     * away, which is worse than never showing it.
     */
    @Test
    fun defaultState_showsNoBanner() {
        assertThat(DashboardUiState().showsCaptureBanner).isFalse()
    }

    /**
     * Every state is covered above.
     *
     * A count assertion rather than a comment, because the failure this guards
     * against is a *new* enum value quietly inheriting the silent branch — which
     * is the safe-looking default and the wrong one if the new state means
     * "broken".
     */
    @Test
    fun everyHealthState_hasABannerDecision() {
        val decided = setOf(
            NotificationCaptureHealth.NOT_GRANTED,
            NotificationCaptureHealth.DEAD,
            NotificationCaptureHealth.CONNECTED,
            NotificationCaptureHealth.RECONNECTING,
            NotificationCaptureHealth.UNAVAILABLE,
        )

        assertThat(decided).containsExactlyElementsIn(NotificationCaptureHealth.entries)
    }
}
