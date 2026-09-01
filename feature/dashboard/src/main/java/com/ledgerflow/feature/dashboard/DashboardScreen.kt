package com.ledgerflow.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.ListenerHealth
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth

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
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    DashboardScreen(
        state = state,
        onSetUpNotifications = onSetUpNotifications,
        modifier = modifier,
    )
}

/**
 * Home (SPEC.md §9.3).
 *
 * The real content -- recent entries, quick stats, budget rings -- reads from
 * `daily_rollup`, which arrives with the rollup worker at P3. Until then the
 * body says so rather than showing invented figures.
 *
 * §5.2's health banner is the screen's first real content, and it sits **above**
 * the empty state rather than inside it. The two are unrelated: "no entries yet"
 * is a fact about the ledger, and "capture is not working" is a fact about the
 * pipeline that fills it. Folding the second into the first is precisely the
 * confusion `CLAUDE.md` §7 forbids — a dead listener must not look like an empty
 * Inbox, and by the same argument it must not look like an empty ledger.
 */
@Composable
public fun DashboardScreen(
    state: DashboardUiState,
    onSetUpNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "Home")

        if (state.showsCaptureBanner) {
            CaptureHealthBanner(
                health = state.captureHealth,
                onAction = onSetUpNotifications,
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            )
        }

        LfEmptyState(
            title = "Nothing here yet",
            body = "Your recent spending, budgets and quick stats appear here once " +
                "there are entries to summarise.",
        )
    }
}

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
private fun DashboardDeadPreview() {
    LfTheme {
        DashboardScreen(
            state = DashboardUiState(captureHealth = NotificationCaptureHealth.DEAD),
            onSetUpNotifications = {},
        )
    }
}
