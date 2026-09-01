package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import org.junit.Test

/**
 * What the More tab's notification row says, per health state (SPEC.md §5.2).
 *
 * The row is the standing route back to the explainer once the first-run
 * presentation has been dismissed, so it is the surface a user reaches for when
 * they suspect capture is broken — which makes its subtitle the answer to a
 * question, not a label.
 *
 * The sibling test [DeletedRowTest] records the same lesson for the bin row: a
 * Settings row that says nothing about its own state is one people open to find
 * out.
 */
class CaptureRowTest {

    private fun subtitleFor(health: NotificationCaptureHealth) =
        captureSubtitle(MoreUiState(captureHealth = health))

    /**
     * Every state says something different.
     *
     * The failure this catches is two branches collapsing onto one sentence —
     * most plausibly `NOT_GRANTED` and `DEAD`, which are the two the *user* has
     * to tell apart and the two whose fixes differ. Telling someone to grant a
     * permission they already granted is how a status line stops being read.
     */
    @Test
    fun everyState_saysSomethingDifferent() {
        val subtitles = NotificationCaptureHealth.entries.map { subtitleFor(it) }

        assertThat(subtitles.toSet()).hasSize(NotificationCaptureHealth.entries.size)
    }

    @Test
    fun everyState_saysSomething() {
        NotificationCaptureHealth.entries.forEach { health ->
            assertThat(subtitleFor(health)).isNotEmpty()
        }
    }

    /**
     * The unhealthy states name the consequence, not the mechanism.
     *
     * "Off" alone does not tell the user that payments are being missed, which
     * is the only fact that makes the row worth tapping. The mechanism is what
     * the screen behind the row is for.
     */
    @Test
    fun theUnhealthyStates_nameTheConsequence() {
        assertThat(subtitleFor(NotificationCaptureHealth.NOT_GRANTED)).contains("missed")
        assertThat(subtitleFor(NotificationCaptureHealth.DEAD)).contains("Stopped")
    }

    /**
     * The pre-poll value claims nothing about the grant.
     *
     * [NotificationCaptureHealth.RECONNECTING] is both a real transient state
     * *and* the value the row renders before the first poll returns, so its
     * sentence has to be true of both. Asserting the absence of "Off" and "On"
     * is the point: either would be a guess presented as a fact for the fraction
     * of a second before the answer arrives.
     */
    @Test
    fun theUnpolledState_claimsNothingAboutTheGrant() {
        val subtitle = subtitleFor(NotificationCaptureHealth.RECONNECTING)

        assertThat(subtitle).doesNotContain("Off")
        assertThat(subtitle).doesNotContain("On.")
    }

    /** The row's default, which is what the screen renders on first composition. */
    @Test
    fun defaultState_rendersTheUnpolledSubtitle() {
        assertThat(captureSubtitle(MoreUiState()))
            .isEqualTo(subtitleFor(NotificationCaptureHealth.RECONNECTING))
    }
}
